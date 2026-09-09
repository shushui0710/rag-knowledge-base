package com.liushuwen.rag.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册分页插件。
 * 【设计要点】分页插件：PaginationInnerInterceptor 拦截分页查询自动改写 SQL（limit/offset），DbType 决定方言
 * 【常见问题】逻辑删除在哪生效？——@TableLogic 由 MP 自动追加 WHERE deleted=0，无需此处配置；为何用 InnerInterceptor？——可链式叠加多插件（分页+乐观锁等）
 */
@Configuration
public class MybatisPlusConfig {

    // 功能：注册 MP 拦截器并加装 MySQL 分页内部插件｜要点：分页 SQL 自动改写，DbType 指定方言
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
