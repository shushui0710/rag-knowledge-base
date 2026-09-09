package com.liushuwen.rag.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 自动填充处理器：insert/update 时自动填充时间字段。
 * 【设计要点】MetaObjectHandler：实体字段标 @TableField(fill=...) 后，MP 在写库前后回调填充，免去手动 set 时间
 * 【常见问题】strictFill 与 fillStrategy？——strict 仅当字段为 null 才填（不覆盖已设值）；为何用 @Component？——注册为 Spring 组件由 MP 自动发现
 */
@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /** 插入时自动填充 createTime 与 updateTime（仅字段为 null 时）。 */
    @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("MyBatis-Plus自动填充: createTime, updateTime");
        this.strictInsertFill(metaObject, "createTime", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
    }

    /** 更新时自动填充 updateTime（仅字段为 null 时）。 */
    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("MyBatis-Plus自动填充: updateTime");
        this.strictUpdateFill(metaObject, "updateTime", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
    }
}
