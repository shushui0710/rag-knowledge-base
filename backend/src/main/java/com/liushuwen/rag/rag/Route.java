package com.liushuwen.rag.rag;

/**
 * 意图路由结果（阶段3-轻量决策）
 *
 * 用一次便宜的 LLM 调用，判断问题该走哪条链路：
 * - DOCUMENT : 查知识库文档内容（走 RAG）
 * - STATS    : 查数据统计（走工具）
 * - HYBRID   : 两者都要（走完整 Agent）
 */
public enum Route {
    DOCUMENT,
    STATS,
    HYBRID
}
