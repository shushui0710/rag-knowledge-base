package com.liushuwen.rag.agent;

import java.util.List;

/**
 * Agent 执行结果：回答文本 + 生成该回答所依据的证据片段。
 * 【设计要点】反思评审必须拿到证据：CriticService 的评判标准之一是"是否有知识库依据"，
 * 若只把最终文本交给评委而证据列表为空，任何回答都会被判"无依据"，反思退化为一次
 * 无信息量的重写（实测会把准确的统计数字替换成模型的模糊复述，甚至截断）。
 * 【常见问题】为什么证据随结果返回而不是让 Agent 暴露 getter？——Agent 是单例，
 * 暴露可变状态会引入线程安全问题；把证据作为返回值随结果传递，天然线程安全。
 *
 * @param answer   回答文本
 * @param evidence 依据片段（工具输出 / 检索到的文档块内容），不可为 null
 */
public record AgentResult(String answer, List<String> evidence) {

    public AgentResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** 无依据支撑的结果（如纯降级文案） */
    public static AgentResult of(String answer) {
        return new AgentResult(answer, List.of());
    }

    /** 带依据的结果 */
    public static AgentResult of(String answer, List<String> evidence) {
        return new AgentResult(answer, evidence);
    }
}
