package com.liushuwen.rag.agent;

import java.util.List;
import java.util.Map;

/**
 * 多 Agent 抽象（阶段4）
 *
 * 每个专用 Agent 聚焦一类任务，工具少、Prompt 聚焦 → 决策准、成本低。
 *
 * 面试考点：
 * - 多 Agent 相比单 Agent：每 Agent 决策空间小，上下文干净
 * - Spring 会把所有 Agent 实现注入 List<Agent>（策略模式）
 */
public interface Agent {

    /** Agent 类型（对应 Route 枚举） */
    AgentType type();

    /**
     * 执行任务
     *
     * @param task    分派来的任务（可能是原始问题，也可能是拆解后的子任务）
     * @param history 会话历史（List of {"role":..,"content":..}）
     * @return 结果文本
     */
    String execute(String task, List<Map<String, Object>> history);

    enum AgentType {
        DOCUMENT,   // 文档问答（RAG）
        STATS,      // 数据查询（工具）
        REPORT      // 报告生成
    }
}
