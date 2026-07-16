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

@Tag(name = "文档管理")
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "上传文档")
    @PostMapping("/upload")
    public Result<Document> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(documentService.upload(file));
    }

    @Operation(summary = "获取文档列表")
    @GetMapping("/list")
    public Result<List<Document>> list() {
        return Result.success(documentService.list());
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.success();
    }

    @Operation(summary = "触发文档向量化入库")
    @PostMapping("/embed/{id}")
    public Result<Void> embed(@PathVariable Long id) {
        documentService.embed(id);
        return Result.success();
    }
}
