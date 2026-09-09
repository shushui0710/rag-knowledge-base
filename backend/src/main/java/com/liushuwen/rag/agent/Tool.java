package com.liushuwen.rag.agent;

import java.util.Map;

/**
 * Agent 工具抽象：定义可被 LLM 调用的"可执行函数"契约，LLM 依据 description 决策何时调用。
 * 【设计要点】策略模式 + Function Calling：Tool 是策略接口，parametersJsonSchema 驱动 LLM 参数生成
 * 【常见问题】为什么工具描述要写清"边界"？——LLM 只靠 description 决策，边界不清会误调用或漏调用
 */
public interface Tool {

    /** 工具唯一名称，如 "query_document_stats" */
    String name();

    /**
     * 工具描述（给 LLM 看）：决定 LLM 何时调用本工具。
     * 【设计要点】Prompt 即工具描述质量：触发场景 + 作用 + 边界（何时不用），边界不清会误/漏调用
     * 【常见问题】描述写错会怎样？——LLM 误判调用时机，该用不用或滥用，答非所问
     */
    String description();

    /**
     * 参数 JSON Schema（OpenAI Function Calling 格式），驱动 LLM 生成结构化入参。
     * 【设计要点】Schema 即 LLM 入参契约：无参数工具返回 {"type":"object","properties":{},"required":[]}
     * 【常见问题】LLM 生成的参数不合法怎么办？——execute 内解析兜底与类型转换，失败回填错误文案
     */
    String parametersJsonSchema();

    /**
     * 执行工具，返回字符串结果。
     *
     * @param arguments 由 LLM 依据 parametersJsonSchema 生成的参数 Map
     * @return 结果字符串，封装为 role=tool 消息回填 LLM，使其继续推理或作答
     */
    String execute(Map<String, Object> arguments);
}
