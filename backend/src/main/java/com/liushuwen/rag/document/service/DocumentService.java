package com.liushuwen.rag.document.service;

import com.liushuwen.rag.document.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档模块服务契约：定义上传、列表、删除、向量化、增量重解析与索引重建的业务接口。
 * 位于 Controller 与实现类之间，调用方只依赖接口，屏蔽 MinIO/Milvus 等存储细节。
 * 【设计要点】面向接口编程：Controller 依赖 DocumentService 而非实现类，便于替换实现与 Mock 单测
 * 【常见问题】为什么 embed 是独立接口而不是上传时自动执行？——向量化耗时长，与上传解耦便于失败重试与幂等控制
 */
public interface DocumentService {

    /**
     * 上传文档：MinIO 存原始文件 + MySQL 落元数据，返回文档记录
     */
    Document upload(MultipartFile file, String category);

    /**
     * 查询当前用户的文档列表（按创建时间倒序）
     */
    List<Document> list();

    /**
     * 按 ID 删除文档（@TableLogic 逻辑删除）
     */
    void delete(Long id);

    /**
     * 触发文档向量化入库：读分块 → 批量 Embedding → 写 Milvus
     */
    void embed(Long id);

    /**
     * 增量更新：重传/重解析指定文档时只重算该文档，不影响其他文档的向量。
     * 流程：删旧分块 → 删旧向量 → 重新解析 → 重新向量化入库
     * 【设计要点】增量 vs 全量：单文档重算避免整库重建的高昂 Embedding 费用与耗时
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
