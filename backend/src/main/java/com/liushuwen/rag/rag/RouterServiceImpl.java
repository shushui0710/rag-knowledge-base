package com.liushuwen.rag.rag;

import com.liushuwen.rag.chat.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 意图路由实现：调 LLM 做结构化分类，把用户问题映射到 DOCUMENT/STATS/HYBRID 三条链路。
 * 【设计要点】LLM 结构化输出 + 低温度求稳：temperature=0.1 压低随机性，让分类稳定可复现；解析容错把非法输出降级为 DOCUMENT
 * 【常见问题】为什么非法输出也归 DOCUMENT？——宁可多检索也不漏答，路由失败兜底 DOCUMENT 保证主流程永远有结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterServiceImpl implements RouterService {

    private final LlmService llmService;

    @Override
    public Route route(String question) {
        try {
            String out = llmService.chatWithSystem(
                    "你是意图分类器。判断用户问题需要的能力："
                            + "DOCUMENT=检索知识库文档内容（问题问的是文档里的知识点）；"
                            + "STATS=查询文档统计/列表/数量/分类（如'有多少文档''列出文档''有哪些文档''文档统计'）；"
                            + "HYBRID=两者都要（既问数量又问内容）。只输出一个词，不要解释。",
                    question, 0.1);
            if (out.contains("STATS") && out.contains("DOCUMENT")) {
                return Route.HYBRID;
            }
            if (out.contains("STATS")) {
                return Route.STATS;
            }
            return Route.DOCUMENT;
        } catch (Exception e) {
            // 功能：路由调用失败时兜底返回 DOCUMENT｜要点：旁路降级（fail-safe 保证主流程可用）
            log.warn("路由调用失败，默认回落 DOCUMENT: {}", e.getMessage());
            return Route.DOCUMENT;
        }
    }
}
