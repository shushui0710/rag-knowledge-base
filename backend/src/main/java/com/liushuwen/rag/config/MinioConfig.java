package com.liushuwen.rag.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 配置：读取 minio.* 配置并创建 MinioClient 单例。
 * 【设计要点】@ConfigurationProperties 松散绑定：yml kebab-case（access-key）自动映射到 camelCase 字段（accessKey）
 * 【常见问题】@Bean 创建的对象为何只执行一次？——Spring 容器启动时实例化并托管，后续依赖注入复用，避免重复建连
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    private String endpoint;  // MinIO 服务地址，yml: minio.endpoint（默认 http://localhost:9000）

    private String accessKey;  // 访问密钥，yml: minio.access-key（默认 minioadmin）

    private String secretKey;  // 密钥，yml: minio.secret-key（默认 minioadmin）

    private String bucketName; // 存储桶，yml: minio.bucket-name（默认 rag-documents）

    /**
     * 创建 MinioClient 单例并交由 Spring 管理。
     * 【设计要点】SDK 客户端单例：建连成本高，@Bean 托管后全局复用，其他类凭类型注入
     * 【常见问题】builder 模式好处？——链式设置 endpoint/credentials 且对象不可变，避免配置被中途改动
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)       // MinIO服务器地址
                .credentials(accessKey, secretKey)  // 访问密钥
                .build();
    }
}
