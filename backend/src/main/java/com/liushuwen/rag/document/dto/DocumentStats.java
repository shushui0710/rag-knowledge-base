package com.liushuwen.rag.document.dto;

/**
 * 文档统计视图：面向 Agent 统计工具的只读投影对象。
 * 【设计要点】DTO 而非 Entity：统计是"投影"不是实体，用 record 表达不可变值语义，避免把可变实体传出模块边界
 * 【常见问题】为何 withChunk 的口径是"文档数"而不是"分块数"？——512/64 滑窗下一篇文档会产生多块，
 *   数分块行数会得到"共 1 篇文档、有内容分块 2 篇"的自相矛盾结论（这是一处已修复的统计口径缺陷）
 *
 * @param total     当前用户文档总数
 * @param embedded  已完成向量化的文档数（embedding_status = 1）
 * @param withChunk 有内容分块的【文档】数（按 document_id 去重）
 */
public record DocumentStats(long total, long embedded, long withChunk) {
}
