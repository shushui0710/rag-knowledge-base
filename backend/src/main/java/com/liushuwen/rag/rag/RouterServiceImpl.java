package com.liushuwen.rag.rag;

import com.liushuwen.rag.chat.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 意图路由实现：调 LLM 做结构化分类，把用户问题映射到 DOCUMENT/STATS/REPORT/HYBRID 四条链路。
 * 【设计要点】LLM 结构化输出 + 低温度求稳：temperature=0.1 压低随机性，让分类稳定可复现；解析容错把非法输出降级为 DOCUMENT
 * 【常见问题】为什么非法输出也归 DOCUMENT？——宁可多检索也不漏答，路由失败兜底 DOCUMENT 保证主流程永远有结果
 * 【熔断边界】本类是 LLM 的调用方之一，熔断打开时 chatWithSystem 抛 LlmUnavailableException，
 * 由下方 catch(Exception) 兜成 DOCUMENT —— 与路由失败的降级路径共用同一出口，主流程不中断。
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
                            + "REPORT=要求产出一份成文的报告/总结/情况说明（如'生成一份关于XX的报告''写一份XX情况说明'）；"
                            + "HYBRID=两者都要（既问数量又问内容）。只输出一个词，不要解释。",
                    question, 0.1);
            // 功能：解析分类结果 → 映射到 Route｜要点：REPORT 优先判定（它是一种明确的"产物型"意图，
            // 若与 STATS/DOCUMENT 同时出现，用户要的是报告而不是数字或片段）；HYBRID 次之（需要两路能力）
            if (out.contains("REPORT")) {
                return Route.REPORT;
            }
            // 【缺陷修复·HYBRID 标签被吞】Prompt 要求"只输出一个词"，模型完全可能直接回标签本身 "HYBRID"。
            // 修复前这里只认 "STATS + DOCUMENT 同时出现" 这一种写法：模型若回 "HYBRID"，
            // 既不命中 STATS 分支也不命中 DOCUMENT 分支，会被静默降级成 DOCUMENT
            // ——"既问数量又问内容"的问题只拿到资料片段，统计数字整段丢失，且日志上看不出异常。
            // 因此把标签本身也纳入识别（与原判定并列，语义完全一致，不改变既有行为）。
            if (out.contains("HYBRID") || (out.contains("STATS") && out.contains("DOCUMENT"))) {
                return Route.HYBRID;
            }
            if (out.contains("STATS")) {
                return Route.STATS;
            }
            return Route.DOCUMENT;
        } catch (Exception e) {
            // 功能：路由调用失败时兜底返回 DOCUMENT｜要点：旁路降级（fail-safe 保证主流程可用）；
            // 熔断打开时走的也是这一条，路由不因 LLM 不可用而中断
            log.warn("路由调用失败，默认回落 DOCUMENT: {}", e.getMessage());
            return Route.DOCUMENT;
        }
    }
}
