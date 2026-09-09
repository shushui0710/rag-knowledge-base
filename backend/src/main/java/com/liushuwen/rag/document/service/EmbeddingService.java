package com.liushuwen.rag.document.service;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liushuwen.rag.common.BusinessException;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding 服务：调用智谱 AI 把文本转成 2048 维向量，供 Milvus 写入与查询向量化使用。
 * 位于分块之后、向量入库/检索之前，是语义检索的"翻译层"。
 * 【设计要点】Embedding 原理：语义相近的文本向量距离更近（"苹果手机"≈"iPhone"，与水果"苹果"远），模型 embedding-3 输出 2048 维
 * 【常见问题】为什么用 List&lt;float[]&gt; 批量接口？——批量调用摊薄网络开销与 token 成本；维度 2048 由谁定？——请求参数 dimensions 显式指定，必须与 Milvus collection schema 一致
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    @Value("${embedding.zhipu.api-key}")
    private String apiKey;

    @Value("${embedding.zhipu.model}")
    private String model;

    @Value("${milvus.dimension}")
    private int dimension;

    // 智谱一次最多处理64条文本
    private static final int BATCH_SIZE = 64;

    // 由Spring容器注入（RestTemplateConfig中定义的@Bean + Spring Boot自动配置的ObjectMapper）
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 批量文本向量化：内部按 64 条/批切片调用智谱 API 后合并结果。
     * 输入：["文本块1", "文本块2", ...]
     * 输出：[[0.12, 0.34, ...2048个], [0.56, 0.78, ...], ...]
     * 【设计要点】客户端分批：BATCH_SIZE=64 对齐服务商单请求上限，subList 切片零拷贝
     * 【常见问题】一批失败会怎样？——embedBatch 抛 BusinessException 整体终止，调用方按文档级幂等重跑
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        List<float[]> allVectors = new ArrayList<>();

        // 功能：按 64 条一批切片调用｜要点：规避服务商单请求上限，subList 视图切片零拷贝
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, end);
            allVectors.addAll(embedBatch(batch));
        }



        return allVectors;
    }

    /**
     * 调用智谱 embeddings 接口处理一批文本（最多 64 条）。
     * 【设计要点】HTTP 调用三步：组装 JSON 请求体（model/input/dimensions）→ Bearer 鉴权 POST → 反序列化取 data[].embedding
     * 【常见问题】异常怎么处理？——统一包装 BusinessException 向上传播，由上层决定重试或标记失败；Bearer 鉴权是什么？——Authorization 头携带 API Key 的标准 token 方案
     */
    private List<float[]> embedBatch(List<String> texts) {
        try {
            // 1. 构建请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 功能：组装请求体 JSON（model/input/dimensions）｜要点：dimensions 须与 Milvus collection schema 的 2048 维一致

            Map<String,Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", texts);
            body.put("dimensions", dimension);
            String requestBody = objectMapper.writeValueAsString(body);



            // 2. 发送HTTP请求
            String apiUrl = "https://open.bigmodel.cn/api/paas/v4/embeddings";
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class);

            // 3. 解析响应
            // 功能：反序列化响应提取 data[].embedding｜要点：index 与请求顺序对应，stream map 提取向量
            EmbeddingResponse resp = objectMapper.readValue(response.getBody(), EmbeddingResponse.class);
            List<float[]> vectors = resp.getData().stream()
                    .map(EmbeddingItem::getEmbedding)
                    .toList();

            log.info("Embedding完成: {}条文本 → {}个向量", texts.size(), vectors.size());
            return vectors;

        } catch (Exception e) {
            log.error("调用智谱Embedding API失败: {}", e.getMessage());
            throw new BusinessException("文本向量化失败: " + e.getMessage());
        }
    }
    // 功能：智谱 embeddings 响应体的 Java 映射｜要点：@JsonIgnoreProperties 容忍未知字段，避免上游加字段就解析失败
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingResponse {
        private List<EmbeddingItem> data;
        // @Data 已生成 getter/setter
    }

    // 功能：响应 data 数组元素映射｜要点：index 标记请求顺序，embedding 即向量本体
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingItem {
        private int index;
        private float[] embedding;
        // @Data 已生成 getter/setter
    }

    // 功能：Embedding 结果缓存，相同文本不重复计费调 API｜要点：空间换时间

    /** 文本 → 向量缓存（相同文本不重复调 API） */
    private final java.util.Map<String, float[]> embedCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 5000;

    /**
     * 单文本向量化（带缓存）：命中直接返回，未命中才调智谱 API。
     * 【设计要点】computeIfAbsent 原子缓存：key 加 "v1:" 模型版本前缀，模型/维度升级时旧缓存自动失效
     * 【常见问题】容量怎么控制？——超 CACHE_LIMIT=5000 直接 clear，简单可接受，进阶可用 Caffeine 做 LRU 淘汰
     */
    public float[] embedSingle(String text) {
        if (text == null) {
            throw new BusinessException("向量化文本不能为空");
        }
        if (embedCache.size() > CACHE_LIMIT) {
            embedCache.clear();                        // 简单容量控制
        }
        return embedCache.computeIfAbsent("v1:" + text,
                t -> embed(List.of(text)).get(0));     // 未命中才调 API
    }

}
