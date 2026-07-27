package com.liushuwen.rag.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus自动填充处理器 - 自动填充createTime和updateTime字段
 *
 * 讲解要点：
 * 1. 为什么要自动填充？
 *    每次插入/更新数据都要手动设时间很麻烦，还容易忘记
 *    自动填充 = "Spring帮你自动打时间戳，你不用操心"
 *
 * 2. @Component = "把我注册为Spring组件"
 *    和@Service本质一样，只是语义不同：
 *    @Service = 我是业务服务
 *    @Component = 我是通用组件（工具类性质的）
 *
 * 3. MetaObjectHandler 是MyBatis-Plus提供的接口
 *    当执行insert/update时，MyBatis-Plus会自动调用这个处理器
 *    检查实体类上有 @TableField(fill = ...) 标注的字段，然后自动填值
 *
 * 4. strictInsertFill / strictUpdateFill：
 *    "strict" = 如果字段已经有值就不覆盖（防止你手动设的时间被冲掉）
 *    fillStrategy = FieldFill.DEFAULT = 只有字段为null时才填充
 */
@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时自动填充 createTime 和 updateTime
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("MyBatis-Plus自动填充: createTime, updateTime");
        this.strictInsertFill(metaObject, "createTime", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
    }

    /**
     * 更新时自动填充 updateTime
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("MyBatis-Plus自动填充: updateTime");
        this.strictUpdateFill(metaObject, "updateTime", java.time.LocalDateTime.class, java.time.LocalDateTime.now());
    }
}
