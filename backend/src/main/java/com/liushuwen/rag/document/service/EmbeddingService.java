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
 * Embedding服务 - 调用智谱AI把文本转成向量
 *
 * 什么是Embedding？
 * 把文本变成一串数字（向量），语义相近的文本向量距离也近。
 * 比如"苹果手机"和"iPhone"的向量很接近，但和"苹果（水果）"的向量较远。
 * 这样计算机就能"理解"文本的语义，而不只是做关键词匹配。
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
     * 批量文本向量化
     *
     * 输入：["文本块1", "文本块2", ...]
     * 输出：[[0.12, 0.34, ...2048个], [0.56, 0.78, ...], ...]
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        List<float[]> allVectors = new ArrayList<>();

        // ============================================================
        // TODO 3（⭐⭐ 难度）：分批处理
        //
        // 智谱API一次最多64条文本。如果texts有100条，需要分2批：
        //   第1批：texts[0~63]
        //   第2批：texts[64~99]
        //
        // 提示：用 for 循环 + subList()
        //   for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
        //       int end = Math.min(i + BATCH_SIZE, texts.size());
        //       List<String> batch = texts.subList(i, end);
        //       // 调用 embedBatch(batch) 得到向量，加到 allVectors 里
        //   }
        //
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, end);
            allVectors.addAll(embedBatch(batch));
        }



        return allVectors;
    }

    /**
     * 调用智谱API处理一批文本（最多64条）
     * 这个方法已经写好了，你不需要改
     */
    private List<float[]> embedBatch(List<String> texts) {
        try {
            // 1. 构建请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // ============================================================
            // TODO 1（⭐ 难度）：构建请求体JSON
            //
            // 智谱API需要的请求体长这样：
            // {
            //   "model": "embedding-3",
            //   "input": ["文本1", "文本2"],
            //   "dimensions": 2048
            // }
            //

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

            // ============================================================
            // TODO 2（⭐⭐ 难度）：从响应JSON中提取向量数组
            //
            // 响应JSON结构：
            // {
            //   "data": [
            //     {"index": 0, "embedding": [0.12, 0.34, ...2048个数字]},
            //     {"index": 1, "embedding": [0.56, 0.78, ...]}
            //   ]
            // }
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
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    // 智谱API响应的Java映射
    static class EmbeddingResponse {
        private List<EmbeddingItem> data;
        // getter/setter
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingItem {
        private int index;
        private float[] embedding;
        // getter/setter
    }
    
}
