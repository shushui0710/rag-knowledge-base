package com.liushuwen.rag.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String category;

    private String minioPath;

    private Integer chunkCount;

    @TableField("embedding_status")
    private Integer embeddingStatus;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
