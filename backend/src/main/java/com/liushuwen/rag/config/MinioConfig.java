package com.liushuwen.rag.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO配置类 - 从application.yml读取MinIO相关配置，创建MinioClient对象
 *
 * 讲解要点：
 * 1. @Configuration = "我是配置类，Spring启动时会自动执行我里面的方法"
 * 2. @ConfigurationProperties(prefix = "minio") = "把yml里minio开头的配置自动映射到这个类的字段上"
 *    比如yml里 minio.endpoint → MinioConfig.endpoint，minio.access-key → MinioConfig.accessKey
 *    注意：yml里的 access-key (横杠) 会自动转成 accessKey (驼峰)
 * 3. @Bean = "这个方法返回的对象交给Spring管理，以后其他地方可以注入使用"
 *    就像工厂模式：你定义了怎么创建MinioClient，Spring帮你创建并保管
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    /**
     * MinIO服务器地址，从yml读取: minio.endpoint
     * 默认值 http://localhost:9000
     */
    private String endpoint;

    /**
     * 访问密钥，从yml读取: minio.access-key
     * 默认值 minioadmin
     */
    private String accessKey;

    /**
     * 密钥，从yml读取: minio.secret-key
     * 默认值 minioadmin
     */
    private String secretKey;

    /**
     * 存储桶名称，从yml读取: minio.bucket-name
     * 默认值 rag-documents
     */
    private String bucketName;

    /**
     * 创建MinioClient对象并交给Spring管理
     *
     * MinioClient是MinIO官方提供的Java客户端，所有MinIO操作（上传/下载/删除）都通过它执行
     * 就像微信客户端一样——你有了它才能操作微信的各种功能
     *
     * @Bean方法只在Spring启动时执行一次，创建的MinioClient对象会被Spring保管
     * 其他类需要用MinioClient时，只需声明 private final MinioClient minioClient
     * Spring会自动把这里创建的对象注入进去（依赖注入）
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)       // MinIO服务器地址
                .credentials(accessKey, secretKey)  // 访问密钥
                .build();
    }
}
