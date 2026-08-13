package com.liushuwen.rag.document.service;

import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.config.MinioConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.minio.GetObjectArgs;

import java.io.InputStream;

/**
 * MinIO文件存储服务 - 封装MinIO的文件上传/下载等操作
 *
 * 讲解要点：
 * 1. 为什么单独写一个MinioService，而不直接在DocumentServiceImpl里操作MinIO？
 *    - 职责分离：MinioService只管文件存取，DocumentService管文档业务逻辑
 *    - 可复用：以后其他模块也要存文件（比如头像），直接注入MinioService就行
 *    - 好测试：MinioService可以单独测试文件上传逻辑
 *
 * 2. @RequiredArgsConstructor 自动生成构造函数，Spring通过构造函数注入依赖
 *    这行代码相当于帮你写了：
 *    public MinioService(MinioClient minioClient, MinioConfig minioConfig) {
 *        this.minioClient = minioClient;
 *        this.minioConfig = minioConfig;
 *    }
 *    Spring看到构造函数需要MinioClient，就从容器里找MinioClient Bean（MinioConfig里创建的那个）
 *    找到后自动传入——这就是依赖注入的完整闭环！
 *
 * 3. MinIO的概念：
 *    - Bucket（桶） = 文件夹的概念，一个项目一般用一个桶
 *    - Object（对象） = 文件的概念，每个上传的文件是一个Object
 *    - putObject = 上传文件的操作
 *    - bucketExists = 检查桶是否存在的操作
 *    - makeBucket = 创建桶的操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    /**
     * 上传文件到MinIO
     *
     * 流程：
     * 1. 确保存储桶存在（不存在就创建）
     * 2. 生成唯一的存储路径（用时间戳避免文件名冲突）
     * 3. 调用MinioClient.putObject上传文件
     *
     * @param inputStream 文件内容流
     * @param objectName  存储路径（如 "documents/2024/abc.pdf"）
     * @param contentType 文件类型（如 "application/pdf"）
     * @param size        文件大小
     * @return 实际存储路径
     */
    public String uploadFile(InputStream inputStream, String objectName,
                             String contentType, long size) {
        try {
            // Step 1: 确保存储桶存在
            ensureBucketExists();

            // Step 2: 上传文件到MinIO
            // PutObjectArgs.builder() 是MinIO客户端提供的构建器模式
            // 就像点餐一样：你一项一项指定参数（桶名、路径、文件流、大小、类型）
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())  // 存到哪个桶：rag-documents
                            .object(objectName)                   // 存的路径：documents/xxx.pdf
                            .stream(inputStream, size, -1)        // 文件流 + 大小（-1表示未知大小时分块上传）
                            .contentType(contentType)             // 文件类型：application/pdf
                            .build()
            );

            log.info("文件上传成功: bucket={}, object={}", minioConfig.getBucketName(), objectName);
            return objectName;

        } catch (Exception e) {
            log.error("文件上传到MinIO失败: {}", e.getMessage(), e);
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 确保存储桶存在 - 如果不存在就创建
     *
     * 为什么需要这个？
     * 第一次启动项目时，MinIO里还没有rag-documents这个桶
     * 如果直接上传会报错"桶不存在"，所以要先检查并创建
     *
     * 这就像你在文件柜里放文件——得先确保抽屉存在，不存在就先造一个
     */
    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(minioConfig.getBucketName())
                                .build()
                );
                log.info("创建MinIO存储桶: {}", minioConfig.getBucketName());
            }
        } catch (Exception e) {
            log.error("检查/创建MinIO存储桶失败: {}", e.getMessage(), e);
            throw new BusinessException("MinIO存储桶初始化失败");
        }
    }

    /**
     * 根据文件名生成MinIO存储路径
     *
     * 为什么不直接用原始文件名？
     * - 两个用户可能上传同名文件 "报告.pdf"，直接用同名会覆盖
     * - 加时间戳前缀确保唯一性：20260716/报告.pdf
     *
     * @param originalFileName 原始文件名
     * @return MinIO存储路径
     */
    public String generateObjectName(String originalFileName) {
        // 格式：documents/yyyyMMdd/原始文件名
        // 比如：documents/20260716/项目报告.pdf
        String datePath = java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
        );
        return "documents/" + datePath + "/" + originalFileName;
    }

    public byte[] download(String objectName) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioConfig.getBucketName()).object(objectName).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException("文件下载失败: " + e.getMessage());
        }
    }
}
