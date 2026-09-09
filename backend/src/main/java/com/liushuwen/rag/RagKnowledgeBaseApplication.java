package com.liushuwen.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动类：Spring Boot 入口。
 * 【设计要点】@SpringBootApplication 组合注解：等价 @Configuration + @EnableAutoConfiguration + @ComponentScan，负责自动扫描与自动配置
 * 【常见问题】@MapperScan 作用？——批量扫描 Mapper 接口生成代理实现，免去逐接口标 @Mapper；SpringApplication.run 做了什么？——启动容器、刷新上下文、触发自动配置
 */
@SpringBootApplication
@MapperScan("com.liushuwen.rag.**.mapper")
public class RagKnowledgeBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagKnowledgeBaseApplication.class, args);
    }
}
