package com.liushuwen.rag.rag;

/**
 * 意图路由服务：对外暴露"问题→链路"的分类接口，由 RouterServiceImpl 用 LLM 结构化输出实现。
 * 【设计要点】分类即服务化：把路由决策抽成独立接口，便于替换策略（规则/模型）与单测
 * 【常见问题】路由错了会怎样？——错判 STATS 为 DOCUMENT 只是多检索，错判 DOCUMENT 为 STATS 会答非所问，故失败默认兜底 DOCUMENT
 */
public interface RouterService {

    /**
     * 路由判断：输入原始问题，输出应走的链路枚举。
     *
     * @param question 用户问题
     * @return DOCUMENT / STATS / HYBRID
     */
    Route route(String question);
}
