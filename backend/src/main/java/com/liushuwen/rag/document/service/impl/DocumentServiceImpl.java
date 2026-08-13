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
 * 文档服务实现类 - 处理文档上传、列表、删除、向量化等业务逻辑
 *
 * 讲解要点：
 * 现在我们注入了两个依赖：
 * 1. DocumentMapper - 操作MySQL的文档表
 * 2. MinioService - 操作MinIO文件存储
 *
 * 这就展示了依赖注入的好处——DocumentServiceImpl不需要知道
 * MinIO的具体操作细节，只需要调用minioService.uploadFile()
 * 如果以后换掉MinIO（比如用阿里云OSS），只需改MinioService的实现，
 * DocumentServiceImpl完全不用动！这就是"面向接口编程"的力量
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
     * 支持的文件类型白名单
     * 只有这些类型的文件才能上传
     * 白话：我们只认这4种文件格式，其他的不管
     */
    private static final Set<String> ALLOWED_FILE_TYPES = new HashSet<>(Arrays.asList(
            "pdf", "docx", "md", "txt"
    ));

    /**
     * 最大文件大小：50MB（与application.yml里的spring.servlet.multipart.max-file-size对应）
     */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    /**
     * 上传文档
     *
     * 完整流程：
     * 1. 校验文件（格式、大小、是否为空）
     * 2. 上传文件到MinIO
     * 3. 构建Document实体对象
     * 4. 保存元数据到MySQL
     *
     * 讲解要点：
     * - MultipartFile 是Spring提供的接口，封装了上传文件的所有信息
     *   file.getOriginalFilename() = 原始文件名
     *   file.getSize() = 文件大小
     *   file.getInputStream() = 文件内容流
     *   file.getContentType() = MIME类型（如 application/pdf）
     *
     * - 为什么先校验再操作？防御式编程——先确保数据没问题再处理
     *   如果文件有问题还去上传MinIO，浪费资源还可能留下脏数据
     */
    @Override
    public Document upload(MultipartFile file, String category) {
        // ========== Step 1: 文件校验 ==========
        validateFile(file);

        // ========== Step 2: 上传到MinIO ==========
        String originalFileName = file.getOriginalFilename();
        String fileType = extractFileType(originalFileName);

        // 生成MinIO存储路径（避免同名冲突）
        // 比如：documents/20260716/项目报告.pdf
        String objectName = minioService.generateObjectName(originalFileName);

        // 调用MinioService上传文件
        // 这里的try-catch在MinioService内部已经处理了
        // 如果上传失败，MinioService会抛BusinessException，被GlobalExceptionHandler接住
        try {
            String minioPath = minioService.uploadFile(
                    file.getInputStream(),
                    objectName,
                    file.getContentType(),
                    file.getSize()
            );

            // ========== Step 3: 构建Document实体对象 ==========
            // Document实体 = MySQL里document表的一行记录
            // 我们要往数据库存什么信息？看Document.java的字段：
            // - title: 文档标题（从文件名提取，去掉后缀）
            // - fileName: 原始文件名
            // - fileType: 文件类型（pdf/docx/md/txt）
            // - fileSize: 文件大小（字节）
            // - minioPath: MinIO存储路径
            // - userId: 所属用户（从UserContext获取当前登录用户）
            // - chunkCount: 分块数（暂为0，下个任务实现解析分块后更新）
            // - embeddingStatus: 向量化状态（0=待入库）
            Document document = new Document();
            // ============================================================
            // TODO 4（⭐ 难度）：设置真实用户ID
            //
            // 当前代码：document.setUserId(1L);  ← 写死了，所有文档都属于userId=1
            // 应该改为：从 UserContext 获取当前登录用户的ID
            //
            // 提示：
            //   document.setUserId(UserContext.getUserId());
            //
            // 面试考点：
            //   - UserContext.getUserId() 的数据从哪来？
            //     JwtInterceptor 从 JWT 解析出 userId，存入 ThreadLocal
            //   - 为什么要数据隔离？
            //     多用户系统不能让A看到B的文档
            // ============================================================
            document.setUserId(UserContext.getUserId());  // TODO 4: 替换为 UserContext.getUserId()
            document.setTitle(extractTitle(originalFileName));
            document.setFileName(originalFileName);
            document.setFileType(fileType);
            document.setFileSize(file.getSize());
            document.setCategory(category != null ? category : "其他");
            document.setMinioPath(minioPath);
            document.setChunkCount(0);  // 下面解析分块后更新
            document.setEmbeddingStatus(0);  // 0=待入库

            // ========== Step 4: 存MySQL元数据（先存，拿到自增ID） ==========
            // ========== Step 4: 存MySQL元数据（先存，拿到自增ID） ==========
            // 插入后document.id会自动被MyBatis-Plus填上MySQL生成的自增ID
            documentMapper.insert(document);

            // ========== Step 5: 解析文档提取文本 ==========
            // DocumentParserService根据文件类型选择PDFBox/POI/直接读取
            String text = documentParserService.parse(file, fileType);
            log.info("文档解析完成: id={}, textLength={}", document.getId(), text.length());

            // ========== Step 6: 文本分块并存MySQL ==========
            // DocumentChunkService使用滑动窗口算法分块
            int chunkCount = documentChunkService.chunkAndSave(document.getId(), text);

            // 更新文档的分块数量
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
     * 校验上传文件
     *
     * 防御式编程的三道检查：
     * 1. 文件是否为空？（用户没选文件就点了上传）
     * 2. 文件是否太大？（50MB限制，防止撑爆服务器）
     * 3. 文件格式是否支持？（只认pdf/docx/md/txt）
     *
     * 每个检查失败都抛BusinessException，GlobalExceptionHandler会接住
     * 返回给前端：{code: 400, message: "具体错误原因", data: null}
     */
    private void validateFile(MultipartFile file) {
        // 检查1: 文件是否为空
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空，请选择文件后再上传");
        }

        // 检查2: 文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小超过50MB限制，当前大小: "
                    + (file.getSize() / 1024 / 1024) + "MB");
        }

        // 检查3: 文件格式
        String fileType = extractFileType(file.getOriginalFilename());
        if (!ALLOWED_FILE_TYPES.contains(fileType)) {
            throw new BusinessException("不支持的文件格式: " + fileType
                    + "，仅支持: pdf, docx, md, txt");
        }
    }

    /**
     * 从文件名提取文件类型（后缀）
     *
     * "项目报告.pdf" → "pdf"
     * "会议纪要.docx" → "docx"
     * "说明.txt" → "txt"
     *
     * lastIndexOf(".") 找最后一个点的位置
     * substring(pointPos + 1) 取点后面的部分
     * toLowerCase() 转小写（PDF → pdf，统一格式）
     */
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            throw new BusinessException("文件名无效，缺少文件扩展名");
        }
        int dotIndex = fileName.lastIndexOf(".");
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * 从文件名提取文档标题（去掉后缀）
     *
     * "项目报告.pdf" → "项目报告"
     * "会议纪要.docx" → "会议纪要"
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
        // ============================================================
        // TODO 5（⭐ 难度）：按当前用户ID过滤文档列表
        //
        // 当前代码：return documentMapper.selectList(null);  ← 查所有人的文档！
        // 应该改为：用 LambdaQueryWrapper 按 userId 过滤
        //
        // 提示：
        //   LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        //   wrapper.eq(Document::getUserId, UserContext.getUserId())
        //          .orderByDesc(Document::getCreateTime);
        //   return documentMapper.selectList(wrapper);
        //
        // 面试考点：
        //   - 数据隔离：每个用户只能看到自己的文档
        //   - orderByDesc：按创建时间倒序，最新文档排最前
        // ============================================================
        LambdaQueryWrapper<Document> wrapper= new LambdaQueryWrapper<>();
        wrapper.eq(Document::getUserId,UserContext.getUserId())
               .orderByDesc(Document::getCreateTime);
        return documentMapper.selectList(wrapper);  // TODO 5: 替换为按 userId 过滤
    }

    @Override
    public void delete(Long id) {
        // TODO: 还应该删除MinIO文件和Milvus向量（后续完善）
        // 目前只做逻辑删除（deleted从0改为1）
        log.info("删除文档: {}", id);
        documentMapper.deleteById(id);
    }

    @Override
    public void embed(Long id) {
        log.info("开始向量化文档: id={}", id);

        // ============================================================
        // TODO 6（⭐ 难度）：查出文档记录 + 状态检查
        //
        // 提示：
        //   Document document = documentMapper.selectById(id);
        //   if (document == null) → 抛 BusinessException("文档不存在")
        //   if (document.getEmbeddingStatus() == 1) → 抛 BusinessException("文档已向量化")
        //
        // ============================================================
        Document document =documentMapper.selectById(id);
        if(document ==null){
            throw new BusinessException("文档不存在");
            
        }
        if(document.getEmbeddingStatus()==1){
            throw new BusinessException("文档已向量化");
        }

        


        // ============================================================
        // TODO 7（⭐ 难度）：查出该文档的所有文本块
        //
        // 提示：用 documentChunkMapper + LambdaQueryWrapper
        //   LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
        //   wrapper.eq(DocumentChunk::getDocumentId, id)
        //          .orderByAsc(DocumentChunk::getChunkIndex);
        //   List<DocumentChunk> chunks = documentChunkMapper.selectList(wrapper);
        //
        //   if (chunks.isEmpty()) → 抛 BusinessException("文档没有文本块，请先上传并解析")
        // ============================================================
        LambdaQueryWrapper<DocumentChunk> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(DocumentChunk::getDocumentId,id)
                .orderByAsc(DocumentChunk::getChunkIndex);
        List<DocumentChunk> chunks=documentChunkMapper.selectList(wrapper);
        if(chunks.isEmpty()){
            throw new BusinessException("文档没有文本块，请先上传并解析");
        }


        // ============================================================
        // TODO 8（⭐⭐ 难度）：提取文本 → 调用Embedding → 存入Milvus
        //
        // 步骤1：从chunks中提取所有content，组成List<String>
        //   List<String> texts = chunks.stream().map(DocumentChunk::getContent).toList();
        //
        // 步骤2：调用embeddingService.embed(texts)得到向量列表
        //   List<float[]> vectors = embeddingService.embed(texts);
        //
        // 步骤3：提取chunkIds
        //   List<Long> chunkIds = chunks.stream().map(DocumentChunk::getId).toList();
        //
        // 步骤4：调用milvusService.insertVectors()存入Milvus
        //   milvusService.insertVectors(chunkIds, id, texts, vectors);
        // ============================================================
        List<String>texts=chunks.stream().map(DocumentChunk::getContent).toList();
        List<float[]>vectors=embeddingService.embed(texts);
        List<Long>chunkIds=chunks.stream().map(DocumentChunk::getId).toList();
        milvusService.insertVectors(chunkIds,id,texts,vectors);



        // ============================================================
        // TODO 9（⭐ 难度）：更新文档状态
        //
        // 提示：
        //   document.setEmbeddingStatus(1);  // 1=已完成
        //   documentMapper.updateById(document);
        //   log.info("向量化完成: id={}, chunkCount={}", id, chunks.size());
        // ============================================================
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

        // ============================================================
        // TODO 1-1（⭐ 难度）：增量更新三步
        //
        // 【标准答案】完整实现（可直接替换下方"骨架实现"两行）
        //
        // 前置①：MinioService 新增 download 方法：
        //   import io.minio.GetObjectArgs;
        //   public byte[] download(String objectName) {
        //       try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
        //               .bucket(minioConfig.getBucketName()).object(objectName).build())) {
        //           return in.readAllBytes();
        //       } catch (Exception e) {
        //           throw new BusinessException("文件下载失败: " + e.getMessage());
        //       }
        //   }
        //
        // 前置②：DocumentParserService 新增 parse(InputStream, String) 重载：
        //   public String parse(InputStream in, String fileType) {
        //       switch (fileType) {
        //           case "pdf":  return parsePdf(in);
        //           case "docx": return parseDocx(in);
        //           case "txt":
        //           case "md":   return parseText(in);
        //           default:     throw new BusinessException("不支持的文件格式: " + fileType);
        //       }
        //   }
        //   // 说明：parse(MultipartFile, String) 内部就是这三兄弟，重载直接复用私有方法
        //
        // 方法体（⚠️ 参照 upload() 的异常处理模式：catch(Exception) → log.error → throw BusinessException）：
        //   try {
        //       // 步骤1：删 MySQL 旧分块（delete(条件)，不是 deleteById(主键)！）
        //       documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
        //               .eq(DocumentChunk::getDocumentId, id));
        //       // 步骤2：删 Milvus 旧向量（内部 expr = "document_id in [id]"）
        //       milvusService.deleteByDocumentId(id);
        //       // 步骤3：重新解析 + 分块 + 向量化
        //       byte[] data = minioService.download(document.getMinioPath());
        //       String text = documentParserService.parse(new ByteArrayInputStream(data),
        //               document.getFileType());
        //       int chunkCount = documentChunkService.chunkAndSave(id, text);
        //       document.setChunkCount(chunkCount);
        //       document.setEmbeddingStatus(0);
        //       documentMapper.updateById(document);
        //       embed(id);   // 复用已有向量化流程（embed 里会校验"文档已向量化"，状态已重置为0）
        //       log.info("增量更新完成: id={}, chunkCount={}", id, chunkCount);
        //   } catch (BusinessException e) {
        //       throw e;   // 业务异常（如文档不存在、embed 校验失败）原样上抛
        //   } catch (Exception e) {
        //       log.error("增量更新失败: id={}, error={}", id, e.getMessage(), e);
        //       throw new BusinessException("文档增量更新失败: " + e.getMessage());
        //   }
        //
        // 面试考点：
        // - 增量更新 vs 全量重建：文档多时全量重建代价高，增量只动该文档
        // - 顺序：先删旧（MySQL+Milvus）再建新，避免检索到旧内容
        // - 异常处理：BusinessException 原样上抛（GlobalExceptionHandler 转 Result），
        //   其他异常统一包装成 BusinessException（与 upload() 完全一致）
        // - MyBatis-Plus 删除三兄弟：deleteById(主键) / delete(条件) / deleteByMap(字段)
        // ============================================================

        // 骨架实现：仅重置状态（不删数据、不重建），保证可运行且不破坏现有数据。
        // 完整实现时把上面【标准答案】填进来，替换下面两行。
        try {
            // 步骤1：删 MySQL 旧分块（delete(条件)，不是 deleteById(主键)！）
            documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, id));
            // 步骤2：删 Milvus 旧向量（内部 expr = "document_id in [id]"）
            milvusService.deleteByDocumentId(id);
            // 步骤3：重新解析 + 分块 + 向量化
            // ⚠️ parse(InputStream, String) 重载声明 throws IOException（受检异常），
            //    必须在本方法捕获或声明——这里由 catch (Exception) 统一处理
            byte[] data = minioService.download(document.getMinioPath());
            String text = documentParserService.parse(new ByteArrayInputStream(data),
                    document.getFileType());
            int chunkCount = documentChunkService.chunkAndSave(id, text);
            document.setChunkCount(chunkCount);
            document.setEmbeddingStatus(0);
            documentMapper.updateById(document);
            embed(id);   // 复用已有向量化流程（embed 里会校验"文档已向量化"，状态已重置为0）
            log.info("增量更新完成: id={}, chunkCount={}", id, chunkCount);
        } catch (BusinessException e) {
            // 业务异常（如文档不存在、embed 校验失败）原样上抛，GlobalExceptionHandler 统一转 Result
            throw e;
        } catch (Exception e) {
            // 其余异常（含 IOException）统一包装成业务异常，与 upload() 的异常处理模式一致
            log.error("增量更新失败: id={}, error={}", id, e.getMessage(), e);
            throw new BusinessException("文档增量更新失败: " + e.getMessage());
        }

    }
}
