package com.liushuwen.rag.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档分块实体，对应 MySQL document_chunk 表。
 * 保存分块原文，是向量化的事实源：Milvus 里的向量可丢，靠这里全量重灌。
 * 【设计要点】document_id + chunk_index 定位：一个文档切出的多个片段靠这两个字段还原顺序，与向量库中的序号一一对应
 * 【常见问题】分块为什么要冗余存 MySQL 而不是只放向量库？——向量库是可重建的索引层，MySQL 才是可靠事实源（索引重建、引用溯源都依赖它）；chunkIndex 从 0 开始有什么用？——与 Milvus 中的分块顺序对齐，检索命中后可反查原文位置
 */
@Data
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;                  // 自增主键

    private Long documentId;          // 所属文档ID，与 Document.id 关联

    private Integer chunkIndex;       // 分块序号（0 起），与向量库中的顺序对应

    private String content;           // 分块原文，重建索引与引用溯源的事实源

    private Integer charCount;        // 分块字符数，便于观测分块质量

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime; // 插入时 MP 自动填充
}
