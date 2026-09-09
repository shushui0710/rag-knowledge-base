package com.liushuwen.rag.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liushuwen.rag.document.entity.Document;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档表 Mapper：继承 MyBatis-Plus BaseMapper 即得单表 CRUD 能力。
 * 是 Document 实体与 MySQL 之间唯一的持久层通道，逻辑删除/自动填充由 MP 框架层统一拦截。
 * 【设计要点】零 SQL 单表操作：BaseMapper 通过泛型+注解反射生成 SQL，无需手写 XML
 * 【常见问题】什么时候需要写自定义 SQL？——多表 join、复杂聚合或性能敏感查询，用 XML 或 @Select 扩展；@Mapper 的作用？——让 MyBatis 生成动态代理并注册进 Spring 容器
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
