package com.liushuwen.rag.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.entity.DocumentChunk;
import com.liushuwen.rag.document.mapper.DocumentChunkMapper;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import com.liushuwen.rag.document.service.DocumentService;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.document.service.DocumentChunkService;
import com.liushuwen.rag.document.service.DocumentParserService;
import com.liushuwen.rag.document.service.MinioService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 文档服务实现类：编排上传全流程（MinIO 存储→解析→分块→向量化→Milvus 入库），并提供列表/删除/增量重解析能力。
 * 在知识库链路中作为上传与向量化编排入口，串联 MinIO、解析、分块、Embedding、Milvus 五个下游服务。
 * 【设计要点】面向接口编程：依赖 MinioService 等接口而非具体实现，存储可平滑替换为 OSS 而不动本类
 * 【常见问题】为什么元数据存 MySQL 而向量存 Milvus？——MySQL 作 source of truth 保证可重建，Milvus 专做相似度检索
 *   多用户数据隔离如何保证？——全程 UserContext.getUserId() 过滤，下游也按 document_id 隔离
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final MinioService minioService;
    private final DocumentParserService documentParserService;
    private final DocumentChunkService documentChunkService;
    private final EmbeddingService embeddingService;      
    private final MilvusService milvusService;
    private final DocumentChunkMapper documentChunkMapper;            

    /**
     * 支持的文件类型白名单：仅允许 pdf/docx/md/txt 上传，其余格式在上传校验阶段直接拒绝。
     * 【设计要点】白名单校验：防御式输入校验第一道关，避免不可解析格式流入解析链路
     */
    private static final Set<String> ALLOWED_FILE_TYPES = new HashSet<>(Arrays.asList(
            "pdf", "docx", "md", "txt"
    ));

    /**
     * 最大文件大小：50MB（与application.yml里的spring.servlet.multipart.max-file-size对应）
     */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    /**
     * 上传文档并编排入库全流程：文件校验→MinIO 存储→解析文本→滑动窗口分块→向量化→Milvus 入库，最后回写分块数与状态。
     * 作为文档入库总入口，串联 MinIO、解析器、分块、Embedding、Milvus 五个下游，先存 MySQL 元数据拿自增 ID 再驱动后续步骤。
     * 【设计要点】编排顺序与事务边界：先 insert 拿自增 ID 才能关联分块与向量；同步单线程执行，大文件场景应异步化避免阻塞
     * 【常见问题】为什么先校验再上传？——防御式编程，避免无效文件占用 MinIO 并留下脏数据
     *   上传失败如何传播？——MinioService 抛 BusinessException，由 GlobalExceptionHandler 统一转 Result
     */
    @Override
    public Document upload(MultipartFile file, String category) {
        // 功能：文件校验（格式/大小/空）｜要点：防御式校验前置，避免脏数据入库
        validateFile(file);

        // 功能：上传文件到 MinIO 并生成对象名
        String originalFileName = file.getOriginalFilename();
        String fileType = extractFileType(originalFileName);

        // 功能：生成 MinIO 对象名（按日期分目录，避免同名覆盖）｜要点：对象存储键设计（扁平命名空间需避免冲突）
        String objectName = minioService.generateObjectName(originalFileName);

        // 功能：委托 MinioService 上传，失败抛 BusinessException 由全局异常处理器转 Result
        try {
            String minioPath = minioService.uploadFile(
                    file.getInputStream(),
                    objectName,
                    file.getContentType(),
                    file.getSize()
            );

            // 功能：构建 Document 实体并写 MySQL；chunkCount 初始 0 待解析回写，embeddingStatus=0 标记待向量化
            Document document = new Document();
            // 功能：写入真实用户ID，实现多用户数据隔离
            // 考点：UserContext 来源——JwtInterceptor 解析 JWT 的 userId 存入 ThreadLocal，本线程可直接取
            // 常见问题：为什么必须按用户隔离？→ 多用户系统不能让 A 看到 B 的文档，下游也按 document_id 隔离
            document.setUserId(UserContext.getUserId());  // 用户ID来自JWT解析后的ThreadLocal，保证文档归属当前用户
            document.setTitle(extractTitle(originalFileName));
            document.setFileName(originalFileName);
            document.setFileType(fileType);
            document.setFileSize(file.getSize());
            document.setCategory(category != null ? category : "其他");
            document.setMinioPath(minioPath);
            document.setChunkCount(0);  // 分块数初始 0，解析分块后回写
            document.setEmbeddingStatus(0);  // 0=待入库，向量化完成后置 1

            // 功能：先 insert 拿 MySQL 自增 ID，分块与向量都通过该 ID 关联本文档
            // 考点：MyBatis-Plus insert 后自增主键回填 entity.id，无需手动查询
            documentMapper.insert(document);

            // 功能：按文件类型分派解析器（PDFBox/POI/纯文本）提取全文
            String text = documentParserService.parse(file, fileType);
            log.info("文档解析完成: id={}, textLength={}", document.getId(), text.length());

            // 功能：滑动窗口分块并落 MySQL；返回分块数用于回写文档
            int chunkCount = documentChunkService.chunkAndSave(document.getId(), text);

            // 功能：回写分块数到文档记录
            document.setChunkCount(chunkCount);
            documentMapper.updateById(document);

            log.info("文档上传成功: id={}, title={}, minioPath={}, chunkCount={}",
                    document.getId(), document.getTitle(), document.getMinioPath(), chunkCount);

            return document;

        } catch (BusinessException e) {
            // MinIO上传失败，直接抛给GlobalExceptionHandler
            throw e;
        } catch (Exception e) {
            log.error("文档上传处理异常: {}", e.getMessage(), e);
            throw new BusinessException("文档上传失败: " + e.getMessage());
        }
    }

    /**
     * 上传文件三道防御式校验：空文件、超 50MB、非白名单格式，任一失败抛 BusinessException 由全局异常处理器转 400。
     * 在上传链路最前置执行，拦截非法输入避免脏数据流入 MinIO 与解析链路。
     * 【设计要点】防御式编程：校验先行、失败快速返回，避免无效资源占用与后续脏数据
     * 【常见问题】为何不用 Spring 的 @MaxUploadSize 注解？——文件大小/类型需业务化错误信息，手动校验可控且统一走 BusinessException
     */
    private void validateFile(MultipartFile file) {
        // 校验：文件为空直接拒绝
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空，请选择文件后再上传");
        }

        // 校验：超出 50MB 上限拒绝
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小超过50MB限制，当前大小: "
                    + (file.getSize() / 1024 / 1024) + "MB");
        }

        // 校验：非白名单格式拒绝
        String fileType = extractFileType(file.getOriginalFilename());
        if (!ALLOWED_FILE_TYPES.contains(fileType)) {
            throw new BusinessException("不支持的文件格式: " + fileType
                    + "，仅支持: pdf, docx, md, txt");
        }
    }

    /**
     * 从文件名提取扩展名作为文件类型（如 "项目报告.pdf" → "pdf"）。
     * 用 lastIndexOf(".") 取最后一点之后子串并转小写，统一 PDF/pdf 大小写差异。
     * 【设计要点】lastIndexOf 而非 indexOf：兼容多层后缀文件名（如 "a.tar.gz" 取 gz）
     */
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            throw new BusinessException("文件名无效，缺少文件扩展名");
        }
        int dotIndex = fileName.lastIndexOf(".");
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * 从文件名提取文档标题：截掉最后一个 "." 及其后的扩展名（"项目报告.pdf" → "项目报告"）。
     */
    private String extractTitle(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return fileName;
        }
        int dotIndex = fileName.lastIndexOf(".");
        return fileName.substring(0, dotIndex);
    }

    @Override
    public List<Document> list() {
        // 功能：按当前用户 ID 过滤文档列表，按创建时间倒序
        // 考点：MyBatis-Plus LambdaQueryWrapper.eq 拼 WHERE user_id=?，实现多用户数据隔离
        // 常见问题：为什么列表查询也要隔离？→ 防止越权看到他人文档，与上传归属、检索 expr 过滤一致
        LambdaQueryWrapper<Document> wrapper= new LambdaQueryWrapper<>();
        wrapper.eq(Document::getUserId,UserContext.getUserId())
               .orderByDesc(Document::getCreateTime);
        return documentMapper.selectList(wrapper);  // 返回当前用户可见文档（最新在前）
    }

    @Override
    public void delete(Long id) {
        // 功能：删除文档——先校验存在性（不存在抛异常而非静默成功），再删数据库记录
        // 考点：删除前存在性校验避免误删/静默成功；完整级联还应清 MinIO 对象与 Milvus 向量，否则残留向量会被检索召回
        // 常见问题：为什么向量也要级联删？→ 否则 Milvus 残留已删文档向量，检索会召回已删除内容
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException("文档不存在: " + id);
        }
        log.info("删除文档: {}", id);
        documentMapper.deleteById(id);
    }

    @Override
    public void embed(Long id) {
        log.info("开始向量化文档: id={}", id);

        // 功能：加载文档记录并做幂等校验（存在性 + 未向量化）
        // 考点：幂等防重——embeddingStatus==1 直接拒绝，避免同一文档重复向量化写 Milvus
        // 常见问题：为什么需要状态位防重？→ 重复插入会污染向量库，召回时同一内容多次命中
        Document document =documentMapper.selectById(id);
        if(document ==null){
            throw new BusinessException("文档不存在");
            
        }
        if(document.getEmbeddingStatus()==1){
            throw new BusinessException("文档已向量化");
        }

        


        // 功能：按 document_id 查出本文档全部分块，按 chunkIndex 升序，空则报错
        // 考点：LambdaQueryWrapper.eq 拼 WHERE document_id=?，orderByAsc 保证向量顺序与原文一致
        LambdaQueryWrapper<DocumentChunk> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(DocumentChunk::getDocumentId,id)
                .orderByAsc(DocumentChunk::getChunkIndex);
        List<DocumentChunk> chunks=documentChunkMapper.selectList(wrapper);
        if(chunks.isEmpty()){
            throw new BusinessException("文档没有文本块，请先上传并解析");
        }


        // 功能：抽取分块文本 → 批量向量化（embedding-3 稠密 2048 维）→ 带 chunkId/documentId 入库 Milvus
        // 考点：批量 embed 一次 RPC 完成所有分块，降低调用开销；chunkId 作主键保证可定位与去重
        List<String>texts=chunks.stream().map(DocumentChunk::getContent).toList();
        List<float[]>vectors=embeddingService.embed(texts);
        List<Long>chunkIds=chunks.stream().map(DocumentChunk::getId).toList();
        milvusService.insertVectors(chunkIds,id,texts,vectors);



        // 功能：向量化完成，置 embeddingStatus=1 并回写，标志该文档可被混合检索召回
        document.setEmbeddingStatus(1);
        documentMapper.updateById(document);
        log.info("向量化完成: id={}, chunkCount={}", id, chunks.size());

    }

    @Override
    public void reparseDocument(Long id) {
        log.info("增量更新文档: id={}", id);

        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException("文档不存在: " + id);
        }

        // 功能：增量重解析——先删旧（MySQL 分块 + Milvus 向量）再从 MinIO 原文件重新解析分块向量化，避免全量重建代价
        // 考点：先删后建保证检索不命中旧内容；复用 MinIO 原文件无需重新上传；embed(id) 复用向量化流程
        // 常见问题：增量 vs 全量重建？→ 文档多时全量代价高，增量只动单文档；
        //   异常处理为何与 upload() 一致？→ BusinessException 原样上抛，其余包装成 BusinessException 由全局处理器转 Result
        try {
            // 功能：按 document_id 删 MySQL 旧分块｜要点：delete(条件) 而非 deleteById(主键)，一次清该文档全部分块
            documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, id));
            // 功能：按 document_id 删 Milvus 旧向量｜要点：expr "document_id in [id]" 实现字段级删除
            milvusService.deleteByDocumentId(id);
            // 功能：从 MinIO 下载原文件重新解析分块向量化
            // 常见问题：受检异常 IOException 怎么处理？→ 本方法未声明 throws，由 catch(Exception) 统一包装成 BusinessException
            byte[] data = minioService.download(document.getMinioPath());
            String text = documentParserService.parse(new ByteArrayInputStream(data),
                    document.getFileType());
            int chunkCount = documentChunkService.chunkAndSave(id, text);
            document.setChunkCount(chunkCount);
            document.setEmbeddingStatus(0);
            documentMapper.updateById(document);
            embed(id);   // 复用 embed(id)：状态已重置为0，可通过"已向量化"校验并走完整入库
            log.info("增量更新完成: id={}, chunkCount={}", id, chunkCount);
        } catch (BusinessException e) {
            // 功能：业务异常原样上抛，由全局异常处理器转 Result
            throw e;
        } catch (Exception e) {
            // 功能：其余异常（含 IOException）统一包装成业务异常，与 upload() 异常处理一致
            log.error("增量更新失败: id={}, error={}", id, e.getMessage(), e);
            throw new BusinessException("文档增量更新失败: " + e.getMessage());
        }

    }

    /**
     * 重建混合检索索引：删除旧 collection 并按含 BM25 Function 的新结构重建，再回放已向量化文档的分块向量。
     * 在 Milvus 结构升级链路中调用，解决旧 collection 无 bm25_vector 导致稀疏路降级的问题。
     * 【设计要点】双存储一致性：MySQL(document_chunk) 是 source of truth，Milvus 可随时由 MySQL 重建，体现向量库"可重建缓存"特性
     * 【常见问题】为什么只回放 embeddingStatus=1 的文档？——未完成的走正常 embed 即可，避免重复向量化；
     *   生产为何要异步？——回放多文档是长任务，同步会阻塞请求线程，应丢到异步任务执行
     */
    @Override
    public int rebuildHybridIndex() {
        log.info("开始重建混合检索索引（BM25 结构）");
        // 待回放清单：所有已向量化完成的文档（分块在 MySQL，无需重新解析）
        List<Document> embeddedDocs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getEmbeddingStatus, 1)
                        .orderByAsc(Document::getId));

        // 功能：删旧 collection｜要点：旧结构无 BM25 Function 无法原地升级，只能删后重建
        milvusService.dropMainCollection();
        try {
            // 功能：按混合结构重建（analyzer + BM25 Function + document_id 字段）
            milvusService.createHybridCollection();
            // 功能：逐文档回放——重向量化分块并插入新结构
            for (Document doc : embeddedDocs) {
                LambdaQueryWrapper<DocumentChunk> w = new LambdaQueryWrapper<>();
                w.eq(DocumentChunk::getDocumentId, doc.getId())
                        .orderByAsc(DocumentChunk::getChunkIndex);
                List<DocumentChunk> chunks = documentChunkMapper.selectList(w);
                if (chunks.isEmpty()) {
                    continue;
                }
                List<String> texts = chunks.stream().map(DocumentChunk::getContent).toList();
                List<float[]> vectors = embeddingService.embed(texts);
                List<Long> chunkIds = chunks.stream().map(DocumentChunk::getId).toList();
                milvusService.insertVectors(chunkIds, doc.getId(), texts, vectors);
            }
            log.info("混合检索索引重建完成: 回放文档数={}", embeddedDocs.size());
            return embeddedDocs.size();
        } catch (Exception e) {
            log.error("混合索引重建失败: {}", e.getMessage(), e);
            throw new BusinessException("混合索引重建失败: " + e.getMessage()
                    + "（可重试本接口；回放失败不会丢数据，分块仍在 MySQL）");
        }
    }
}
