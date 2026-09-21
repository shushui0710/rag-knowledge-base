package com.liushuwen.rag.document.controller;

import com.liushuwen.rag.common.Result;
import com.liushuwen.rag.document.dto.IndexRebuildStatus;
import com.liushuwen.rag.document.dto.IndexRebuildTask;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.service.DocumentService;
import com.liushuwen.rag.document.service.IndexRebuildService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档管理接口层：上传、列表、删除、向量化与索引重建的 REST 入口。
 * 位于 HTTP 请求与 Service 之间，只做参数接转与 Result 包装，不含业务逻辑。
 * 【设计要点】multipart 上传：@RequestParam("file") MultipartFile 接收文件流，分类参数 defaultValue="其他" 兜底
 * 【常见问题】Result 统一包装的好处？——前端拿到一致的 code/message/data 结构，异常也经全局处理器转成同构响应；@Tag/@Operation 干什么用？——Knife4j/Swagger 按注解生成分组接口文档，省去手写文档
 */
@Tag(name = "文档管理")
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final IndexRebuildService indexRebuildService;

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
     * 受理索引重建（异步）：校验 ADMIN 角色与二次确认串后立即返回任务快照，长任务在后台线程执行。
     * 【设计要点】运维型接口 + 异步受理：72.3s 的同步回放已实测会占满请求线程，故改为"受理即返回"，
     *   进展由同路径的 GET 轮询；请求体必须携带 {"confirm":"CONFIRM-REBUILD"} 以拦住误触
     * 【常见问题】为什么 POST 和 GET 用同一个路径？——它们描述的是同一个资源（索引重建任务）的
     *   "触发"与"当前状态"两个动作，REST 语义下按 HTTP 方法区分即可，且不会新增端点路径
     */
    @Operation(summary = "触发索引重建（异步受理，仅 ADMIN 角色；body 需带 confirm=CONFIRM-REBUILD）")
    @PostMapping("/rebuild-index")
    public Result<IndexRebuildTask> rebuildIndex(@RequestBody Map<String, String> body) {
        return Result.success(indexRebuildService.submit(body == null ? null : body.get("confirm")));
    }

    /**
     * 查询索引重建状态：返回"当前账号是否可运维"与最近一次任务快照。
     * 【设计要点】无权者拿到的 allowed=false 且 task 为 null（不侧漏运维信息），接口本身始终成功，
     *   避免普通用户打开文档页时因权限不足被弹错
     */
    @Operation(summary = "查询索引重建任务状态与当前账号的运维权限")
    @GetMapping("/rebuild-index")
    public Result<IndexRebuildStatus> rebuildIndexStatus() {
        return Result.success(indexRebuildService.status());
    }
}
