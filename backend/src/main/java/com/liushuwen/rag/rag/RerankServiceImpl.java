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
 * 重排序实现：调用智谱 rerank API 对粗召回候选做交叉编码器精排，取 topN 进 Prompt。
 * 【设计要点】交叉编码器 rerank vs 双塔向量：rerank 把 query+doc 联合编码，相关性判别细于向量相似度
 * 【常见问题】rerank 接口挂了怎么办？——降级按原向量分数排序取前 N，主流程不中断；常见问题：为何不重试？省延迟、且原分数排序已是可用次优解
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankServiceImpl implements RerankService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.retrieval.rerank-base-url:https://open.bigmodel.cn/api/paas/v4/rerank}")
    private String rerankUrl;

    // 功能：复用智谱 API Key（rerank 与 embedding 同账户）｜要点：凭据复用降低配置冗余
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
            // 功能：构造智谱 rerank 请求体（独立接口，非 OpenAI 兼容；模型名 rerank/rerank-pro）｜要点：第三方 API 协议差异
            // 常见问题：为何要截断文本到 500 字？→ 控制请求体积与计费，超长片段对精排增益有限
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "rerank");
            body.put("query", query);
            body.put("documents", candidates.stream()
                    .map(c -> c.getContent() == null ? ""
                            : (c.getContent().length() > 500
                            ? c.getContent().substring(0, 500) : c.getContent()))   // 空值+截断防御：避免空指针并控请求体积
                    .collect(Collectors.toList()));
            body.put("top_n", topN);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(zhipuApiKey);
            // 功能：序列化请求体并封装带 Bearer 鉴权的 HttpEntity｜要点：受检异常已在本方法 try 内被吞，无需 throws 声明
            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(body), headers);
            Map resp = restTemplate.postForObject(rerankUrl, entity, Map.class);

            // 功能：防御响应或 results 为 null｜要点：外部 API 响应不可信，提前抛异常走降级
            if (resp == null || resp.get("results") == null) {
                throw new IllegalStateException("rerank 响应为空");
            }

            // 功能：解析 results[]（index + relevance_score）并按下标映射回原候选｜要点：索引映射还原顺序
            List<Map> results = (List<Map>) resp.get("results");
            List<MilvusService.SearchResult> ranked = new ArrayList<>();
            for (Map r : results) {
                int idx = ((Number) r.get("index")).intValue();
                if (idx < 0 || idx >= candidates.size()) continue;   // 越界防御：丢弃异常下标
                MilvusService.SearchResult sr = candidates.get(idx);
                sr.setScore(((Number) r.get("relevance_score")).floatValue());  // 用重排新分数覆盖原向量分
                ranked.add(sr);
            }
            return ranked;
        } catch (Exception e) {
            // 功能：API 失败/解析异常时按原向量分数排序取前 N｜要点：fail-safe 降级保主流程可用
            // 常见问题：降级会损失什么？→ 失去语义精排增益，但召回集未变，答案不会"错"只是可能不最优
            log.warn("Rerank API 失败，降级按原分数取前{}: {}", topN, e.getMessage());
            return candidates.stream()
                    .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                    .limit(topN)
                    .collect(Collectors.toList());
        }
    }
}
