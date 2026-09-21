package com.liushuwen.rag.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.dto.DocumentStats;
import com.liushuwen.rag.document.entity.DocumentChunk;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import com.liushuwen.rag.document.service.DocumentChunkService;
import com.liushuwen.rag.document.service.DocumentParserService;
import com.liushuwen.rag.document.service.DocumentService;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.document.service.MinioService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.util.Objects;

/**
 * 文档服务实现类：编排上传全流程（MinIO 存储→解析→分块→向量化→Milvus 入库），并提供列表/删除/增量重解析能力。
 * 在知识库链路中作为上传与向量化编排入口，串联 MinIO、解析、分块、Embedding、Milvus 五个下游服务。
 * 【设计要点】面向接口编程：依赖 MinioService 等接口而非具体实现，存储可平滑替换为 OSS 而不动本类
 * 【常见问题】为什么元数据存 MySQL 而向量存 Milvus？——MySQL 作 source of truth 保证可重建，Milvus 专做相似度检索
 *   多用户数据隔离如何保证？——全程 UserContext.getUserId() 过滤，下游也按 document_id 隔离
 * 【职责边界】本类只做"文档业务"；全库索引重建属运维动作，已移出本类（见 IndexRebuildService）
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
     * 文件名长度上限：与 document.file_name / title 的 VARCHAR(200) 对齐。
     * 【缺陷修复·超长输入落 500（A7 补射程发现）】长度越界不命中任何业务校验，
     * 会一路走到 INSERT 才被数据库拒绝——抛出的不是 BusinessException，
     * 而是落进 GlobalExceptionHandler 的兜底分支，对外表现成 HTTP 500「系统内部错误」，
     * 把"文件名太长"误报成"服务端故障"（与 A1-13 的 404、A2-12 的 413 是同一类 HTTP 语义错位）。
     */
    private static final int MAX_FILENAME_LENGTH = 200;

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

        // 校验：文件名长度不得超过列上限（否则会在落库阶段炸成 500，见常量注释）
        String originalName = file.getOriginalFilename();
        if (originalName != null && originalName.length() > MAX_FILENAME_LENGTH) {
            throw new BusinessException("文件名过长，不得超过 " + MAX_FILENAME_LENGTH
                    + " 个字符，当前 " + originalName.length() + " 个");
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

    /**
     * 级联删除文档：一次调用清掉该文档在**三处存储**上的全部痕迹。
     *
     * 【缺陷修复·级联删除缺失（原 A6-03 缺口②）】原实现只有一行 documentMapper.deleteById(id)，
     * 于是 document_chunk 分块行、Milvus 向量、MinIO 原文件**三项全部残留**：
     *   - Milvus 残留最危险——它不知道 MySQL 的逻辑删除，按已删文档的 document_id 仍能召回原文；
     *   - 主链路之所以"看起来没事"，是因为 documentIds 取自 MySQL（deleted=0），已删文档不在其中，
     *     这个过滤恰好把脏数据挡住了 —— 这正是缺口长期未被发现的原因。
     *
     * 【设计要点】清理顺序不是随意的：先做**外部存储**（此时 MySQL 尚未动，失败可整体回滚），
     * 再做 **MySQL 分块 + 文档行**（这一对才是事务能保护的部分）。若把 MySQL 放前面，
     * 一旦 Milvus 清理失败就只剩"文档已删、向量还在"的不可恢复脏状态。
     * 【常见问题】为什么 MinIO 清理失败只告警不中断？→ 孤儿对象只占存储成本、不影响检索正确性；
     * 而 Milvus 向量残留会污染检索结果，故前者容忍、后者失败即中止。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 功能：存在性校验（不存在抛异常而非静默成功）
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException("文档不存在: " + id);
        }
        // 功能：归属校验——文档只能由归属者删除
        // 考点：只校验"已登录"远远不够，任何登录用户都能拿别人的 id 删掉（典型 IDOR 越权）
        if (!Objects.equals(document.getUserId(), UserContext.getUserId())) {
            throw new BusinessException(403, "无权删除他人文档: " + id);
        }

        // ① Milvus 向量：正确性关键项，失败即中止（残留会被检索召回）
        milvusService.deleteByDocumentId(id);

        // ② MinIO 原文件：成本项，失败只告警（孤儿对象不该卡住删除）
        try {
            minioService.deleteFile(document.getMinioPath());
        } catch (Exception e) {
            log.warn("MinIO 对象清理失败（孤儿对象不影响检索，继续删除）: id={}, path={}, error={}",
                    id, document.getMinioPath(), e.getMessage());
        }

        // ③ MySQL 分块：物理删除；④ 文档行：逻辑删除（@TableLogic 自动改写为 UPDATE）
        documentChunkService.deleteByDocumentId(id);
        documentMapper.deleteById(id);
        log.info("文档级联删除完成: id={}, minioPath={}", id, document.getMinioPath());
    }

    @Override
    public List<Document> listByCategory(String category, int limit) {
        // 功能：按分类列出当前用户文档（Agent 文档列表工具的唯一数据入口）
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Document::getUserId, UserContext.getUserId());      // 数据隔离，必加！
        if (category != null && !category.isBlank()) {
            wrapper.eq(Document::getCategory, category);
        }
        // limit 由服务端固定传入（非用户输入），此处仅做下限保护，避免生成非法 SQL
        wrapper.orderByDesc(Document::getCreateTime).last("limit " + Math.max(1, limit));
        return documentMapper.selectList(wrapper);
    }

    @Override
    public DocumentStats stats() {
        // 功能：当前用户文档统计——总数 / 已向量化数 / 有内容分块的文档数
        // 考点：三处口径都限定在 user_id 内，统计同样受多租户隔离约束
        Long userId = UserContext.getUserId();
        long total = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>().eq(Document::getUserId, userId));
        long embedded = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId)
                        .eq(Document::getEmbeddingStatus, 1));
        // document_chunk 无 user_id 字段，靠 document_id 归属：先取本用户文档 id，再按 document_id 去重计数
        // （口径集中在 DocumentChunkService#countDocumentsWithChunk，避免"同一系统两套统计口径"）
        List<Long> docIds = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                        .select(Document::getId)
                        .eq(Document::getUserId, userId))
                .stream().map(Document::getId).toList();
        return new DocumentStats(total, embedded, documentChunkService.countDocumentsWithChunk(docIds));
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
        // 功能：归属校验——向量化是**写操作**（改文档状态位 + 把内容写进向量库），只能作用于自己的文档
        // 【缺陷修复·IDOR 写越权（A7 补射程发现）】修复前本方法只校验「文档存在 + 未向量化」，
        // 与 delete() 的归属校验形成不对称：任何登录用户拿着他人的文档 id 就能替对方触发向量化。
        // 旧套件全绿的原因是越权用例按「逐点补」的方式只覆盖了删除与会话，
        // 没有按「身份 × 端点」成矩阵去覆盖写操作（见 A7-05 权限矩阵）。
        if (!Objects.equals(document.getUserId(), UserContext.getUserId())) {
            throw new BusinessException(403, "无权操作他人文档: " + id);
        }
        if(document.getEmbeddingStatus()==1){
            throw new BusinessException("文档已向量化");
        }

        


        // 功能：按 document_id 查出本文档全部分块，按 chunkIndex 升序，空则报错
        // 考点：LambdaQueryWrapper.eq 拼 WHERE document_id=?，orderByAsc 保证向量顺序与原文一致
        List<DocumentChunk> chunks = documentChunkService.listByDocumentId(id);
        if (chunks.isEmpty()) {
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
        // 功能：归属校验——重解析会先删旧分块与旧向量再重建，属破坏性写操作
        // 【同源缺陷·A7 补射程发现】与原 embed() 同一处疏漏：只校验存在性、不校验归属。
        // 该方法当前没有 HTTP 入口（见 A6-04 缺口③），但一旦被接进产品，越权面立刻放大；
        // 故与 delete / embed 保持同一口径，把校验内聚在服务层，而不是指望每个入口自己记得校验。
        if (!Objects.equals(document.getUserId(), UserContext.getUserId())) {
            throw new BusinessException(403, "无权操作他人文档: " + id);
        }

        // 功能：增量重解析——先删旧（MySQL 分块 + Milvus 向量）再从 MinIO 原文件重新解析分块向量化，避免全量重建代价
        // 考点：先删后建保证检索不命中旧内容；复用 MinIO 原文件无需重新上传；embed(id) 复用向量化流程
        // 常见问题：增量 vs 全量重建？→ 文档多时全量代价高，增量只动单文档；
        //   异常处理为何与 upload() 一致？→ BusinessException 原样上抛，其余包装成 BusinessException 由全局处理器转 Result
        try {
            // 功能：按 document_id 删 MySQL 旧分块｜要点：delete(条件) 而非 deleteById(主键)，一次清该文档全部分块
            documentChunkService.deleteByDocumentId(id);
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

    // 【能力迁出】原 rebuildHybridIndex() 已移出本类，改由 IndexRebuildService / IndexRebuildWorker 承担：
    // 全库重建属"运维动作"而非"文档业务"；且原先同步执行会阻塞请求线程（实测 72.3s），现已改为异步受理。
}
