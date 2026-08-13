package com.liushuwen.rag.rag;

import com.liushuwen.rag.chat.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 查询改写实现（阶段2 ✅ 已实现：LLM 生成检索词）
 *
 * 链路：口语问题 → LLM 改写成 2-3 个关键词短语（| 分隔）→ 空格拼接用于检索。
 * 失败自动降级为原问题，保证问答主流程可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriterServiceImpl implements QueryRewriterService {

    private final LlmService llmService;

    @Override
    public String rewrite(String question) {
        try {
            // 调 LLM 改写（temperature 0.2，输出稳定）
            String raw = llmService.chatWithSystem(
                    "你是检索关键词改写助手。把用户问题改写成2-3个更适合检索的关键词短语，"
                            + "只输出改写结果，用|分隔，不要解释。",
                    question, 0.2);
            // 解析：按 | 或中文逗号/顿号分隔 → trim → 去空 → 空格拼接
            return Arrays.stream(raw.split("[|，,；]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" "));
        } catch (Exception e) {
            // 改写失败：返回原问题兜底（检索用原问题，效果不至于更差）
            log.warn("查询改写失败，返回原问题兜底: {}", e.getMessage());
            return question;
        }
    }
}
