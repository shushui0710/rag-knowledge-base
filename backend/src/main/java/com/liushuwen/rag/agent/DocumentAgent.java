package com.liushuwen.rag.agent;

import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.llm.LlmService;
import com.liushuwen.rag.llm.LlmUnavailableException;
import com.liushuwen.rag.rag.MemoryService;
import com.liushuwen.rag.rag.RetrievalChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文档问答 Agent：专注 RAG 检索问答，**委托全站唯一检索链**（RetrievalChain）后生成回答，并接入长期记忆。
 * 【设计要点】检索增强生成（RAG）：改写降歧 + 混合检索召回 + Rerank 精排，提升答案相关性
 * 【设计要点·为什么检索不写在这里】修复前本类内联了一份与 ChatServiceImpl 几乎逐字相同的检索链，
 * 但**漏了 minScore 阈值过滤**——同样的候选在主链被挡掉、在这里却直接进 Prompt。两条链路的召回口径
 * 悄悄分叉，靠注释承诺"与主链路一致"是保不住的；现统一委托 RetrievalChain。
 * 【常见问题】长期记忆如何回存？——topScore ≥ MemoryService.SAVE_MIN_SCORE 的高质量问答对回存，跨会话按用户隔离召回
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentAgent implements Agent {

    private final LlmService llmService;
    /** 全站唯一检索链：向量化 → 隔离 → 记忆召回 → 改写 → 混合检索 → 重排 → 阈值过滤 */
    private final RetrievalChain retrievalChain;
    /** 长期记忆：高质量问答对回存（召回侧由 RetrievalChain 承担） */
    private final MemoryService memoryService;

    /** 注入 Prompt 的历史条数上限：只取最近 N 条，控制 token 与噪声 */
    private static final int HISTORY_MAX_TURNS = 6;

    /** 单条历史内容的截断长度：历史里可能整段塞过很长的回答，超长会挤掉参考资料 */
    private static final int HISTORY_ITEM_MAX_CHARS = 200;

    @Override
    public AgentType type() {
        return AgentType.DOCUMENT;
    }

    @Override
    public AgentResult execute(String task, List<Map<String, Object>> history) {
        // 功能：委托全站唯一检索链（向量化 → 检索层隔离 → 记忆召回 → 改写 → 混合检索 → Rerank → minScore 过滤）｜要点：与主链路、报告工具共用同一实现，杜绝三份复制分叉
        RetrievalChain.Outcome outcome = retrievalChain.retrieve(task);
        // 功能：区分「向量化失败」与「检索无命中」——两者提示语不同，不能合并成一句"未找到"｜要点：把基础设施故障说成"没有资料"会掩盖问题
        if (outcome.vectorizationFailed()) {
            return AgentResult.of("文档向量化失败，请稍后重试。");
        }
        if (outcome.isEmpty()) {
            return AgentResult.of("未在知识库中找到相关文档，请换个问法或先上传相关文档。");
        }

        // 功能：会话历史前缀 + 【参考N】片段 + 【历史问答记录】两轮上下文都进 Prompt｜要点：多轮上下文指代前文，跨会话长期记忆补连贯性
        String prompt = buildHistoryBlock(history)
                + "请根据以下参考资料回答用户问题：\n\n" + outcome.contextWithMemories()
                + "用户问题：" + task;
        // 【熔断降级】LLM 唯一出口熔断打开时抛 LlmUnavailableException：本 Agent 返回统一兜底文案，
        // AgentResult 照常携带 evidence ⇒ 上层仍能把"检索到的依据"展示给用户，且整条编排链不会中断
        String answer;
        boolean degraded = false;
        try {
            answer = llmService.chat(prompt);
        } catch (LlmUnavailableException e) {
            log.warn("[DocumentAgent] LLM 熔断中，返回兜底文案: {}", task);
            answer = LlmUnavailableException.FALLBACK_MESSAGE;
            degraded = true;
        }

        // 高质量问答对回存长期记忆（topScore 过门槛才存，与主链路共用同一条质量线 MemoryService.SAVE_MIN_SCORE）
        // 降级产生的兜底文案不含任何知识，不得入库（degraded 拦截）
        if (!degraded && outcome.topScore() >= MemoryService.SAVE_MIN_SCORE) {
            memoryService.saveExchange(UserContext.getUserId(), task, answer);
        }
        return AgentResult.of(answer, outcome.evidence());
    }

    /**
     * 把会话历史拼成 Prompt 前缀块，让多轮追问能指代前文（如"那第二篇呢"）。
     * 【设计要点】history 为空时返回空串，Prompt 与单轮完全一致——保证不改变无历史调用的既有行为
     * 【常见问题】为什么不用 LlmService 的多轮 messages 接口？——本 Agent 的 Prompt 是"资料 + 问题"模板，
     * 历史作为一段可读上下文注入最简单直观；真要严格多轮，应改造 LlmService 支持 system/user/assistant 消息列表
     *
     * @param history 会话历史（按时间正序），元素形如 {"role":"user"/"assistant","content":"..."}
     * @return Prompt 前缀（无历史时为空串）
     */
    private String buildHistoryBlock(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        int from = Math.max(0, history.size() - HISTORY_MAX_TURNS);
        for (int i = from; i < history.size(); i++) {
            Map<String, Object> msg = history.get(i);
            if (msg == null || msg.get("content") == null) {
                continue;
            }
            String text = String.valueOf(msg.get("content")).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > HISTORY_ITEM_MAX_CHARS) {
                text = text.substring(0, HISTORY_ITEM_MAX_CHARS) + "...";
            }
            block.append("assistant".equals(msg.get("role")) ? "助手：" : "用户：")
                    .append(text).append("\n");
        }
        return block.length() == 0 ? "" : "【历史对话】\n" + block + "\n";
    }
}
