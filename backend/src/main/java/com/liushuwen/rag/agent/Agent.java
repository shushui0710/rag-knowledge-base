package com.liushuwen.rag.agent;

import java.util.List;
import java.util.Map;

/**
 * 多 Agent 抽象：定义专用 Agent 契约，每 Agent 聚焦一类任务，工具少、Prompt 聚焦，决策准、成本低。
 * 【设计要点】多 Agent vs 单 Agent：决策空间小、上下文干净；Spring 注入 List<Agent> 收集策略实现
 * 【常见问题】为什么拆多 Agent 而非一个大 Prompt？——职责隔离降低幻觉与成本，便于路由分派
 */
public interface Agent {

    /** Agent 类型（对应 Route 枚举） */
    AgentType type();

    /**
     * 执行任务
     *
     * @param task    分派来的任务（可能是原始问题，也可能是拆解后的子任务）
     * @param history 会话历史（List of {"role":..,"content":..}）
     * @return 结果文本 + 生成该文本所依据的证据片段（供上层反思评审核对"是否有据"）
     */
    AgentResult execute(String task, List<Map<String, Object>> history);

    enum AgentType {
        DOCUMENT,   // 文档问答（RAG）
        STATS       // 数据查询（工具）
        // 【为什么没有 REPORT】报告生成能力已由工具 generate_report 承载（ReAct 循环里 LLM 自主调用），
        // 路由（DOCUMENT/STATS/HYBRID）永远产不出 REPORT，保留该类型只会得到一个"装配了却选不中"的死分支。
    }
}
