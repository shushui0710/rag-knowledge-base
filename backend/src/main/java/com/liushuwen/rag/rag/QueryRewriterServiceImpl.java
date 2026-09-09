package com.liushuwen.rag.rag;

import com.liushuwen.rag.chat.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 查询改写实现：调 LLM 把口语问题生成 2-3 个关键词短语，拼接后送检索，提升召回。
 * 【设计要点】LLM 生成检索词 + 失败降级：改写异常时返回原句，主链路无感、可用性不降
 * 【常见问题】改写会不会引入噪声词反而干扰检索？——用低温度(0.2)控稳定，且降级策略保证最坏情况等价于不改写
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriterServiceImpl implements QueryRewriterService {

    private final LlmService llmService;

    @Override
    public String rewrite(String question) {
        try {
            // 功能：调 LLM 改写（temperature 0.2 求输出稳定）｜要点：低温度抑制随机性
            String raw = llmService.chatWithSystem(
                    "你是检索关键词改写助手。把用户问题改写成2-3个更适合检索的关键词短语，"
                            + "只输出改写结果，用|分隔，不要解释。",
                    question, 0.2);
            // 功能：按 | 或中文逗号/顿号切分词语 → trim → 去空 → 空格拼接｜要点：健壮解析（兼容多种分隔符）
            return Arrays.stream(raw.split("[|，,；]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" "));
        } catch (Exception e) {
            // 功能：改写失败返回原问题兜底｜要点：fail-soft 降级（对主链路无感）
            log.warn("查询改写失败，返回原问题兜底: {}", e.getMessage());
            return question;
        }
    }
}
