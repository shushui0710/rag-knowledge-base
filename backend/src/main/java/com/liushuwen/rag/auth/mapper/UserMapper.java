package com.liushuwen.rag.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liushuwen.rag.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper：继承 MyBatis-Plus BaseMapper 获得通用 CRUD。
 * 【设计要点】BaseMapper：MyBatis-Plus 按实体与表名约定自动生成无 XML 的增删改查 SQL
 * 【常见问题】何时需要自定义方法？——复杂联表或定制 SQL 时写 XML / 注解方法；@Mapper 与 @MapperScan 关系？——后者批量扫描免去逐接口标注
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
