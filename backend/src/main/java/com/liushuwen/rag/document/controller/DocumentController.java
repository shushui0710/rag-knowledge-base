package com.liushuwen.rag.document.controller;

import com.liushuwen.rag.common.Result;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理接口层：上传、列表、删除、向量化与索引重建的 REST 入口。
 * 位于 HTTP 请求与 DocumentService 之间，只做参数接转与 Result 包装，不含业务逻辑。
 * 【设计要点】multipart 上传：@RequestParam("file") MultipartFile 接收文件流，分类参数 defaultValue="其他" 兜底
 * 【常见问题】Result 统一包装的好处？——前端拿到一致的 code/message/data 结构，异常也经全局处理器转成同构响应；@Tag/@Operation 干什么用？——Knife4j/Swagger 按注解生成分组接口文档，省去手写文档
 */
@Tag(name = "文档管理")
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档：multipart 接收文件，委托 service 完成存储与落库。
     * 【设计要点】MultipartFile 绑定：file 必传、category 可省略（defaultValue="其他"），Spring 自动做 multipart 解析
     */
    @Operation(summary = "上传文档")
    @PostMapping("/upload")
    public Result<Document> upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "category", defaultValue = "其他") String category) {
        return Result.success(documentService.upload(file, category));
    }

    /**
     * 查询文档列表：返回当前用户未删除文档的元数据
     */
    @Operation(summary = "获取文档列表")
    @GetMapping("/list")
    public Result<List<Document>> list() {
        return Result.success(documentService.list());
    }

    /**
     * 删除文档：按 ID 触发逻辑删除
     */
    @Operation(summary = "删除文档")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.success();
    }

    /**
     * 触发文档向量化入库：读分块 → 批量 Embedding → 写 Milvus（幂等，已向量化会拒绝）
     */
    @Operation(summary = "触发文档向量化入库")
    @PostMapping("/embed/{id}")
    public Result<Void> embed(@PathVariable Long id) {
        documentService.embed(id);
        return Result.success();
    }

    /**
     * 重建混合检索索引：旧 collection 升级为 BM25 结构，并自动回放所有已向量化文档。
     * 【设计要点】运维型接口：schema 变更时以 MySQL 分块文本为事实源全量重灌 Milvus，返回回放文档数
     */
    @Operation(summary = "重建混合检索索引（旧collection升级为BM25结构，自动回放已向量化文档）")
    @PostMapping("/rebuild-index")
    public Result<Integer> rebuildIndex() {
        return Result.success(documentService.rebuildHybridIndex());
    }
}
