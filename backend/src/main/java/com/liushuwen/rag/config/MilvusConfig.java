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
 * Milvus配置类 - 和MinioConfig结构完全一样
 *
 * @ConfigurationProperties(prefix = "milvus") 自动读取 application.yml 里：
 *   milvus.host → host 字段
 *   milvus.port → port 字段
 *   milvus.collection-name → collectionName 字段
 *   milvus.dimension → dimension 字段
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
     * 创建 MilvusServiceClient（Milvus Java SDK 的客户端对象）
     * 和 MinioClient.builder() 一个套路
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
     * 创建 MilvusClientV2（v2 API 客户端，TODO 2-1 路线A：BM25 Function 专用）
     *
     * 为什么需要第二个客户端？
     * - v1 客户端（MilvusServiceClient）没有 v2 方法（FunctionType/EmbeddedText/SearchReq 都是 v2 的）
     * - v1 / v2 是两个独立 Bean，各自连同一个 Milvus，操作同一个 collection，互不影响
     */
    @Bean
    public MilvusClientV2 milvusClientV2() {
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build();
        return new MilvusClientV2(connectConfig);
    }
}
