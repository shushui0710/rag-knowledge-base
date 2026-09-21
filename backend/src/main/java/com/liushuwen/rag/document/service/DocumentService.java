package com.liushuwen.rag.document.service;

import com.liushuwen.rag.document.dto.DocumentStats;
import com.liushuwen.rag.document.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档模块服务契约：定义上传、列表、统计、级联删除、向量化与增量重解析的业务接口。
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
     * 按 ID 删除文档：级联清理 Milvus 向量、MinIO 原文件与 MySQL 分块后，再逻辑删除文档行。
     * 仅文档归属者可删，越权抛业务码 403。
     */
    void delete(Long id);

    /**
     * 按分类查当前用户文档（category 为空则不过滤），按创建时间倒序取前 limit 条。
     * 供 Agent 的文档列表工具使用——工具层不应直连 Mapper。
     */
    List<Document> listByCategory(String category, int limit);

    /**
     * 当前用户文档统计（总数 / 已向量化数 / 有内容分块的文档数）。
     */
    DocumentStats stats();

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
}
