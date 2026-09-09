package com.liushuwen.rag.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档元数据实体，对应 MySQL document 表。
 * 是文档模块的事实源：正文在 MinIO、分块在本模块、向量在 Milvus，都以这里的 id 为锚点关联。
 * 【设计要点】MyBatis-Plus 注解映射：@TableName 绑表、@TableId(type=AUTO) 自增主键回填、@TableLogic 逻辑删除
 * 【常见问题】为什么用逻辑删除而不是物理删除？——删除只标记 deleted=1，可追溯可恢复，也避免关联数据（分块/向量）残留造成不一致；embeddingStatus 有哪几个状态？——0=待入库、1=已向量化，重解析时回退为 0，构成简单状态机
 */
@Data
@TableName("document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;                 // 自增主键，分块与向量都以此关联

    private Long userId;             // 所属用户ID（来自 JWT），多用户数据隔离的依据

    private String title;            // 文档标题（截掉扩展名的文件名）

    private String fileName;         // 原始文件名

    private String fileType;         // 文件类型：pdf/docx/md/txt，决定解析器路由

    private Long fileSize;           // 文件大小（字节）

    private String category;         // 业务分类（默认"其他"），检索过滤维度

    private String minioPath;        // MinIO 对象键，正文文件的实际存储位置

    private Integer chunkCount;      // 分块数量，解析分块后回写

    @TableField("embedding_status")
    private Integer embeddingStatus; // 向量化状态：0=待入库，1=已向量化（重解析回退为0）

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime; // 插入时 MP 自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime; // 插入/更新时 MP 自动填充

    @TableLogic
    private Integer deleted;         // 逻辑删除标记，查询/删除由 MP 自动拼接 deleted 条件
}
