package com.liushuwen.rag.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liushuwen.rag.document.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 重排序实现（阶段2 ✅ 已实现：智谱 rerank API）
 *
 * 链路：粗召回（recallTopK）→ 本服务精排 → 取 topN 进入 Prompt。
 * API 失败自动降级为"按原分数排序"，保证问答主流程可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankServiceImpl implements RerankService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.retrieval.rerank-base-url:https://open.bigmodel.cn/api/paas/v4/rerank}")
    private String rerankUrl;

    /** 复用智谱 key（rerank 与 embedding 同一账户） */
    @Value("${embedding.zhipu.api-key}")
    private String zhipuApiKey;

    @Override
    public List<MilvusService.SearchResult> rerank(String query,
                                                   List<MilvusService.SearchResult> candidates,
                                                   int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            // 1) 构造请求（⚠️ 智谱 rerank 是独立接口，不是 OpenAI 兼容格式；
            //    模型名是 rerank / rerank-pro，不是 bge-reranker-v2-m3）
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "rerank");
            body.put("query", query);
            body.put("documents", candidates.stream()
                    .map(c -> c.getContent() == null ? ""
                            : (c.getContent().length() > 500
                            ? c.getContent().substring(0, 500) : c.getContent()))   // 空值+截断防御
                    .collect(Collectors.toList()));
            body.put("top_n", topN);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(zhipuApiKey);
            // ⚠️ writeValueAsString 抛受检异常 JsonProcessingException——
            //    已在本方法 try-catch(Exception) 内，无需再声明 throws
            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(body), headers);
            Map resp = restTemplate.postForObject(rerankUrl, entity, Map.class);

            // 2) 防御：响应或 results 可能为 null
            if (resp == null || resp.get("results") == null) {
                throw new IllegalStateException("rerank 响应为空");
            }

            // 3) 解析 results[]：index + relevance_score，按 index 映射回原 candidate
            List<Map> results = (List<Map>) resp.get("results");
            List<MilvusService.SearchResult> ranked = new ArrayList<>();
            for (Map r : results) {
                int idx = ((Number) r.get("index")).intValue();
                if (idx < 0 || idx >= candidates.size()) continue;   // 越界防御
                MilvusService.SearchResult sr = candidates.get(idx);
                sr.setScore(((Number) r.get("relevance_score")).floatValue());  // 用新分数
                ranked.add(sr);
            }
            return ranked;
        } catch (Exception e) {
            // 4) 降级：API 失败/解析失败时按原分数排序取前 N（保可用，不炸主流程）
            log.warn("Rerank API 失败，降级按原分数取前{}: {}", topN, e.getMessage());
            return candidates.stream()
                    .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                    .limit(topN)
                    .collect(Collectors.toList());
        }
    }
}
