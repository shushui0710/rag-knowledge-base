package com.liushuwen.rag.llm;
import com.liushuwen.rag.common.BusinessException;

/**
 * LLM 服务暂时不可用：熔断打开期间由唯一出口 LlmService 抛出，提示各链路"本次根本没发出请求，请走兜底"。
 * 【设计要点】继承 BusinessException ⇒ 沿用统一异常体系的 code/message 语义（未被捕获时 → HTTP 400）；
 * 但正式链路上每一处调用方都在本地 catch 并转成用户可见的兜底文案，用户不会看到异常本身。
 * 【常见问题】为什么熔断打开要抛异常，而不是让 LlmService 返回空串/兜底文案？
 *   —— 返回空串会被上层当成"模型给了个空回答"静默吞掉，用户拿到空内容；
 *   抛专属异常才能让每条链路按自己的语义降级：
 *   主问答链给兜底文案、意图路由回落 DOCUMENT、查询改写退回原句、评审放行、ReAct 返回降级提示。
 * 【常见问题】为什么单独建一个异常类型，而不是复用 BusinessException？
 *   —— 熔断是"依赖不可用"，与"参数不合法"这类业务校验错误的语义完全不同；
 *   调用方需要精确区分"是熔断"（不重试、直接兜底）还是"其他失败"（可按各自策略处理）。
 */
public class LlmUnavailableException extends BusinessException {

    /** 用户可见的熔断兜底文案（各链路直接复用，保证全站口径一致） */
    public static final String FALLBACK_MESSAGE = "抱歉，AI 服务暂时不可用，请稍后再试。";

    public LlmUnavailableException() {
        super(FALLBACK_MESSAGE);
    }
}
