package com.liushuwen.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
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
}
