package com.liushuwen.rag.agent;

import java.util.Map;

/**
 * Agent 工具抽象（阶段3核心接口）
 *
 * 一个"工具" = 一个带描述的可执行函数。LLM 通过 description 决定何时调用。
 *
 * 三个关键方法：
 * - name()                  : 工具名（动词+名词，如 query_document_stats）
 * - description()           : 给 LLM 看的说明（触发场景 + 作用）——写不好 LLM 就不调用
 * - parametersJsonSchema()  : OpenAI Function 的参数 JSON Schema
 * - execute(Map)            : 实际执行，返回字符串结果（会回填给 LLM 作为 tool 消息）
 *
 * 面试考点：
 * - 为什么工具描述重要？LLM 靠 description 做决策，描述要写清楚"什么时候用、别什么时候用"
 * - 工具执行结果以 role=tool 消息回填，且必须带 tool_call_id 与调用配对
 */
public interface Tool {

    /** 工具唯一名称，如 "query_document_stats" */
    String name();

    /**
     * 工具描述（给 LLM 看）。
     * 好的描述 = 触发场景 + 作用 + 边界（什么时候不要用）。
     * 例："查询知识库文档统计信息（总数、分类、向量化状态），
     *       当用户问'有多少文档/统计'时使用。注意：问具体内容不要用我。"
     */
    String description();

    /**
     * 参数 JSON Schema（OpenAI Function Calling 格式）。
     * 无参数工具返回：{"type":"object","properties":{},"required":[]}
     */
    String parametersJsonSchema();

    /**
     * 执行工具。
     *
     * @param arguments 由 LLM 生成的参数（来自 parametersJsonSchema 的结构）
     * @return 结果字符串（会被拼成 tool 消息回填给 LLM，让 LLM 继续推理）
     */
    String execute(Map<String, Object> arguments);
}
