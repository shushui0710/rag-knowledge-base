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

    /**
     * 重建混合检索索引：把旧结构 collection 升级为 BM25 混合结构。
     * 流程：删旧 collection → 按 BM25 结构重建 → 回放所有"已向量化"文档
     * （分块文本还在 MySQL，重新 Embedding 后插入新结构）。
     *
     * 触发场景：从 Milvus 2.4 旧结构升级、或 collection schema 变更。
     *
     * @return 回放的文档数
     */
    int rebuildHybridIndex();
}
