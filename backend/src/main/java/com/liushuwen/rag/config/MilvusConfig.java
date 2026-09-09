package com.liushuwen.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 配置：读取 milvus.* 配置并管理两个 SDK 客户端 Bean。
 * 【设计要点】@ConfigurationProperties 松散绑定：yml kebab-case 自动映射到 camelCase 字段，集中读取强类型配置
 * 【常见问题】为何用 @ConfigurationProperties 而非 @Value？——一组相关配置整体绑定成对象，类型安全、可校验、IDE 可提示
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusConfig {

    private String host;
    private int port;
    private String collectionName;
    private int dimension;

    /**
     * 创建 MilvusServiceClient（v1 SDK 客户端）。
     * 【设计要点】SDK 客户端单例：以 @Bean 托管连接对象，避免每次请求新建连接的开销
     * 【常见问题】ConnectParam 与 ConnectConfig 区别？——v1 与 v2 两套 API 各自的连接参数封装
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();
        return new MilvusServiceClient(connectParam);
    }

    /**
     * 创建 MilvusClientV2（v2 API 客户端，BM25 混合检索专用）。
     * 【设计要点】双客户端并存：v1 无 v2 的 FunctionType/SearchReq 等能力，二者各自连同一 Milvus、操作同一 collection、互不影响
     * 【常见问题】为何不统一用一个客户端？——v1/v2 SDK 方法不兼容，按需注入对应版本；客户端是否线程安全？——官方客户端可单例复用
     */
    @Bean
    public MilvusClientV2 milvusClientV2() {
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build();
        return new MilvusClientV2(connectConfig);
    }
}
