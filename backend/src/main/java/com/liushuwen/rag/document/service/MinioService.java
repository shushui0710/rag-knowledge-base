package com.liushuwen.rag.document.service;

import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.config.MinioConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.minio.GetObjectArgs;

import java.io.InputStream;

/**
 * MinIO 文件存储服务：封装对象上传、下载与桶管理，是文档正文落盘的唯一出口。
 * 上传链路中由 DocumentServiceImpl 调用，原始文件以对象键形式存入 S3 兼容的对象存储。
 * 【设计要点】S3 兼容对象存储：MinIO 走 AWS S3 协议，Bucket（桶）+ Object（对象键）两级寻址，可平滑迁移到云上 OSS/COS
 * 【常见问题】为什么抽独立的 MinioService？——职责分离（存取与业务解耦）、可复用（其他模块直接注入）、可单独 Mock 测试；MinioClient 从哪来？——MinioConfig 里 @Bean 创建，经 @RequiredArgsConstructor 构造器注入 Spring 容器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    /**
     * 上传文件到 MinIO，返回对象键。
     * 流程：确保桶存在 → putObject 写入 → 异常统一转 BusinessException 向上传播
     * 【设计要点】MinIO SDK 建造者模式：PutObjectArgs 逐项组装桶/键/流/大小/类型，参数可读且不可变
     * 【常见问题】stream 的 -1 是什么意思？——partSize=-1 表示大小未知时由 SDK 自动分块上传；失败怎么处理？——统一包装 BusinessException，由全局异常处理器转 Result
     *
     * @param inputStream 文件内容流
     * @param objectName  对象键（如 "documents/20260716/abc.pdf"）
     * @param contentType MIME 类型（如 "application/pdf"）
     * @param size        文件大小（字节）
     * @return 对象键（即实际存储路径）
     */
    public String uploadFile(InputStream inputStream, String objectName,
                             String contentType, long size) {
        try {
            // 功能：先确保桶存在｜要点：惰性初始化，避免首次上传因桶缺失失败
            ensureBucketExists();

            // 功能：putObject 写入对象｜要点：SDK 建造者模式组装桶/键/流/大小/类型
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())  // 目标桶：rag-documents
                            .object(objectName)                   // 对象键：documents/xxx.pdf
                            .stream(inputStream, size, -1)        // 文件流 + 大小（-1 表示大小未知时自动分块）
                            .contentType(contentType)             // MIME 类型：application/pdf
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
     * 确保存储桶存在，不存在则创建（首次上传前的惰性初始化）。
     * 【设计要点】桶是对象存储的顶层命名空间：项目级一个桶即可，桶内靠对象键区分层级
     * 【常见问题】为什么不放在应用启动时初始化？——惰性创建避免启动强依赖 MinIO 可用性；并发下重复建桶怎么办？——makeBucket 幂等，已存在时抛可识别异常，多实例同时创建也安全
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
     * 生成对象键：documents/yyyyMMdd/原始文件名（如 documents/20260716/项目报告.pdf）。
     * 【设计要点】对象键设计：日期前缀天然按天分区，便于排查与生命周期管理；保留原始文件名便于人工识别
     * 【常见问题】为什么不直接用原始文件名？——不同用户传同名文件会覆盖，日期前缀只能缓解，强唯一可再加 UUID/用户ID；对象存储里有真目录吗？——没有，"目录"只是键名前缀
     *
     * @param originalFileName 原始文件名
     * @return MinIO 存储路径
     */
    public String generateObjectName(String originalFileName) {
        // 格式：documents/yyyyMMdd/原始文件名
        // 比如：documents/20260716/项目报告.pdf
        String datePath = java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
        );
        return "documents/" + datePath + "/" + originalFileName;
    }

    /**
     * 按对象键读取文件内容为字节数组。
     * 【设计要点】try-with-resources：getObject 返回的网络流必须关闭，否则连接泄漏；小文件一次 readAllBytes 即可
     * 【常见问题】大文件怎么办？——应改为返回 InputStream 流式转发，避免整文件载入内存；失败怎么传播？——包装 BusinessException 由全局异常处理器统一处理
     */
    public byte[] download(String objectName) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioConfig.getBucketName()).object(objectName).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new BusinessException("文件下载失败: " + e.getMessage());
        }
    }

    /**
     * 按对象键删除文件：文档删除链路里对象存储侧的级联清理入口。
     * 【设计要点】三处存储的失败代价不同，故策略不同：Milvus 向量残留会被检索召回（**正确性问题**，
     *   必须失败即中止）；MinIO 孤儿对象只占存储成本（**成本问题**），由调用方决定是否容忍。
     *   本方法只如实报告成功/失败，不替调用方做取舍。
     * 【常见问题】对象不存在会怎样？——S3 语义下 removeObject 对不存在的键也返回成功，天然幂等；
     *   objectName 为空时直接返回，避免把 null 传进 SDK 触发参数异常。
     */
    public void deleteFile(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return;                                   // 空值防御：无对象键视为无需清理
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioConfig.getBucketName())
                    .object(objectName)
                    .build());
            log.info("文件删除成功: bucket={}, object={}", minioConfig.getBucketName(), objectName);
        } catch (Exception e) {
            log.error("文件删除失败: object={}, error={}", objectName, e.getMessage(), e);
            throw new BusinessException("文件删除失败: " + e.getMessage());
        }
    }
}
