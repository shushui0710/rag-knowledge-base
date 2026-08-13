package com.liushuwen.rag.document.service;

import com.liushuwen.rag.document.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    Document upload(MultipartFile file, String category);

    List<Document> list();

    void delete(Long id);

    void embed(Long id);

    /**
     * 增量更新：重传文档时只重算该文档（阶段1 TODO）
     * 流程：删旧分块 → 删旧向量 → 重新解析 → 重新向量化入库
     *
     * @param id 文档ID
     */
    void reparseDocument(Long id);
}
