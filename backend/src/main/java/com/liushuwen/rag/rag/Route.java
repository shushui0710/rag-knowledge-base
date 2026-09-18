package com.liushuwen.rag.rag;

/**
 * 意图路由枚举：标记一次用户提问应走的检索/工具链路，是 RAG 管线的轻量决策入口。
 * 【设计要点】意图路由（DOCUMENT/STATS/REPORT/HYBRID）：用一次低成本 LLM 调用分流，避免所有问题都跑完整 Agent
 * 【常见问题】为什么不每次都走完整链路？——STATS 类问题（如"有多少文档"）用工具比向量检索更准更快；路由能省 token、降延迟
 * 【REPORT 分支的由来】报告生成（工具 generate_report）原先只存在于 ReAct 循环里，而 ReAct 只有引擎直连端点
 * /api/agent/ask 可达 ⇒ 用户在对话页永远触发不到，能力等于不存在。把 REPORT 纳入路由后，
 * 「生成一份关于 XX 的报告」就会走 AgentType.REPORT（内部即 ReAct 循环），能力正式接进产品链路。
 */
public enum Route {
    DOCUMENT,
    STATS,
    REPORT,
    HYBRID
}
