package com.liushuwen.rag.agent;

import com.alibaba.fastjson.JSONObject;
import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct 循环执行器：驱动"思考→调工具→观察→再思考"直到产出最终答案或达轮数上限。
 * 【设计要点】ReAct 范式与 Function Calling 的关系：FC 是能力、ReAct 是编排循环；maxIterations=5 防死循环
 * 【设计要点】本类只负责"编排循环"，不承担指标记账：LLM 计数在 LlmService、工具计数在 ToolRegistry.execute、
 * 问答次数/耗时在入口层（AgentController）。修复前本类既记 LLM 数又按轮数补记，是"重复计数"的根源。
 * 【常见问题】为什么要轮数上限？——防 LLM 重复调用死循环、控制成本与延迟；超限降级返回提示
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutor {

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final RagProperties ragProperties;
    private final LlmCircuitBreaker circuitBreaker;

    /**
     * 执行一次 Agent 问答：熔断前置 + ReAct 循环 + 兜底。
     * 【设计要点】tryAcquire 熔断前置：调用前先问熔断器防雪崩；循环内 onSuccess/onFailure 闭环
     * 【常见问题】工具调用失败为何不抛异常？——回填错误文案让 LLM 自我修正，而非中断循环
     *
     * @param userQuestion 用户问题
     * @return 最终回答（或降级/兜底文案）
     */
    public String execute(String userQuestion) {
        if (!circuitBreaker.tryAcquire()) {
            return "抱歉，AI 服务暂时不可用，请稍后再试。";
        }

        try {
            // 功能：ReAct 循环（思考→行动→观察→再思考）｜要点：maxIterations 上限防 LLM 死循环
            // 常见问题：为什么需要轮数上限？→ 防止重复调用工具死循环，控制成本与延迟
            List<Map<String, Object>> messages = new ArrayList<>();
            // 功能：每条消息带 type 字段（message/tool）｜要点：兼容 OpenAI 的 type 必填，漏填返回 400
            // 常见问题：tool 消息为何要 tool_call_id？→ 必须匹配前置 assistant 的 tool_calls，否则 400
            Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
            userMsg.put("type", "message");
            userMsg.put("role", "user");
            userMsg.put("content", userQuestion);
            messages.add(userMsg);
            int iterations = 0;

            while (iterations++ < ragProperties.getAgent().getMaxIterations()) {
                // 功能：调 LLM 决策（返回 ANSWER 或 TOOL_CALL）｜要点：本类不再记 LLM 计数——LlmService 是唯一出口，在那里记才不漏不重
                LlmService.LlmResponse resp = llmService.chatWithTools(messages, toolRegistry.all());

                // LLM 认为可以回答了 → 直接返回
                if (resp.isAnswer()) {
                    circuitBreaker.onSuccess();
                    return resp.getContent();
                }

                // 功能：回填含 tool_calls 的 assistant 消息｜要点：OpenAI 要求 tool 消息前必有对应 assistant 消息，漏填 400
                messages.add(resp.getRawAssistantMsg());

                // 功能：逐个执行工具，结果以 role=tool 消息回填｜要点：tool_call_id 配对使 LLM 读到执行结果
                // 【设计要点】工具统一经 toolRegistry.execute 执行（查表+执行+计数唯一出口），本类不再自行记工具数
                for (LlmService.ToolCall call : resp.getToolCalls()) {
                    String result;
                    try {
                        // arguments 是 JSON 字符串，先解析再传给工具
                        result = toolRegistry.execute(call.getFunction().getName(),
                                JSONObject.parseObject(call.getFunction().getArguments()));
                    } catch (Exception e) {
                        // 功能：工具失败回填错误文案｜要点：让 LLM 自我修正而非中断 ReAct 循环
                        result = "工具执行失败：" + e.getMessage() + "，请调整参数或换一种方式";
                    }
                    Map<String, Object> toolMsg = new java.util.LinkedHashMap<>();
                    toolMsg.put("type", "tool");              // 必须带 type 字段（deepseek 兼容层要求）
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", call.getId());
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
            }
            // 超过 maxIterations 仍没出答案 → 降级提示
            return "这个问题步骤较多，请拆分成几个小问题再问。";

        } catch (Exception e) {
            log.error("[AgentExecutor] 执行失败: {}", e.getMessage(), e);
            circuitBreaker.onFailure();
            return "抱歉，处理你的问题时出了点状况，请稍后重试。";
        }
    }
}
