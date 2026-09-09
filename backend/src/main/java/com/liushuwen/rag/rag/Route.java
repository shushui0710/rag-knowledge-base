package com.liushuwen.rag.rag;

/**
 * 意图路由枚举：标记一次用户提问应走的检索/工具链路，是 RAG 管线的轻量决策入口。
 * 【设计要点】意图路由（DOCUMENT/STATS/HYBRID）：用一次低成本 LLM 调用分流，避免所有问题都跑完整 Agent
 * 【常见问题】为什么不每次都走完整链路？——STATS 类问题（如"有多少文档"）用工具比向量检索更准更快；路由能省 token、降延迟
 */
public enum Route {
    DOCUMENT,
    STATS,
    HYBRID
}
