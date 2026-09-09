package com.liushuwen.rag.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体：映射 user 表，承载认证与基本信息。
 * 【设计要点】逻辑删除：@TableLogic 将 deleted 字段转为软删除，查询自动追加条件，避免物理删数据
 * 【常见问题】@TableField(fill=...) 做什么？——配合 MetaObjectHandler 在 insert/update 时自动填充时间；id 为何自增？——单机自增简单可靠，分布式场景需雪花算法
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键，数据库自增

    private String username; // 登录用户名，唯一约束

    private String password; // BCrypt 密文（内嵌随机盐），永不明文存储

    private String nickname; // 昵称，注册时默认等于用户名

    private String email; // 邮箱

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime; // 创建时间，插入时自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime; // 更新时间，插入/更新时自动填充

    @TableLogic
    private Integer deleted; // 逻辑删除标记，0 正常 1 已删除
}
