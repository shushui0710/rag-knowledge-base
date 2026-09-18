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
        STATS,      // 数据查询（工具）
        // 【REPORT 为什么曾经被删、又为什么加回来】
        // 报告生成能力由工具 generate_report 承载（ReAct 循环里 LLM 自主调用），而当时路由只产得出
        // DOCUMENT/STATS/HYBRID，永远产不出 REPORT ⇒ 该类型是"装配了却选不中"的死分支，故被删除。
        // 但删除只解决了"死分支"，没解决根因：generate_report 只能被 AgentExecutor 的 ReAct 循环调到，
        // 而那条链路没有产品入口 ⇒ 报告生成对用户而言等于不存在（已实现未接入的缺口）。
        // 修复方式是把路由扩出第 4 类 REPORT，并用 ReportAgent 承接（内部复用 AgentExecutor 的 ReAct 循环）：
        // 死分支变成活分支，报告能力与单 Agent 的 ReAct 能力一起接进对话页。
        REPORT      // 报告生成（ReAct + generate_report 工具）
    }
}
