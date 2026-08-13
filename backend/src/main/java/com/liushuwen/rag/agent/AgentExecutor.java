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
 * Agent 执行器（阶段3核心：ReAct 循环）
 *
 * 负责把"思考→行动→观察→再思考"循环跑起来：
 *   1. 调 LLM（带工具定义）
 *   2. LLM 返回 ANSWER → 直接返回最终答案
 *   3. LLM 返回 TOOL_CALL → 执行工具，结果回填，继续循环
 *   4. 超过 maxIterations 轮 → 返回降级提示
 *
 * 骨架说明：当前实现为"直通"（直接调 LLM 返回，保证可运行），
 * 填充后成为完整 ReAct 循环（阶段3-3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutor {

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final RagProperties ragProperties;
    private final LlmCircuitBreaker circuitBreaker;
    private final AgentMetrics metrics;

    /**
     * 执行一次 Agent 问答
     *
     * @param userQuestion 用户问题
     * @return 最终回答
     */
    public String execute(String userQuestion) {
        long start = System.currentTimeMillis();
        if (!circuitBreaker.tryAcquire()) {
            metrics.recordQuery(System.currentTimeMillis() - start, 0, 0);
            return "抱歉，AI 服务暂时不可用，请稍后再试。";
        }

        try {
            // ============================================================
            // 阶段3 ✅ 已实现：ReAct 循环（思考→行动→观察→再思考）
            // ============================================================
            List<Map<String, Object>> messages = new ArrayList<>();
            // ⚠️ deepseek-v4-flash 要求每条消息带 type 字段（message/tool），2026-08 验收实测
            Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
            userMsg.put("type", "message");
            userMsg.put("role", "user");
            userMsg.put("content", userQuestion);
            messages.add(userMsg);
            int iterations = 0;
            int toolCount = 0;

            while (iterations++ < ragProperties.getAgent().getMaxIterations()) {
                LlmService.LlmResponse resp = llmService.chatWithTools(messages, toolRegistry.all());
                metrics.recordLlmCall();

                // LLM 认为可以回答了 → 直接返回
                if (resp.isAnswer()) {
                    metrics.recordQuery(System.currentTimeMillis() - start, iterations, toolCount);
                    circuitBreaker.onSuccess();
                    return resp.getContent();
                }

                // ① 关键：先把模型返回的 assistant 消息（含 tool_calls）原样回填，
                //    漏了必报 400（tool 消息必须匹配前置 assistant 的 tool_calls）
                messages.add(resp.getRawAssistantMsg());

                // ② 逐个执行工具，结果作为 role=tool 消息回填
                for (LlmService.ToolCall call : resp.getToolCalls()) {
                    String result;
                    try {
                        Tool tool = toolRegistry.get(call.getFunction().getName());
                        if (tool == null) {
                            result = "错误：工具不存在 " + call.getFunction().getName();
                        } else {
                            // arguments 是 JSON 字符串，先解析再传给工具
                            result = tool.execute(JSONObject.parseObject(call.getFunction().getArguments()));
                        }
                    } catch (Exception e) {
                        // 工具失败也回填错误信息，让 LLM 换个方式继续
                        result = "工具执行失败：" + e.getMessage() + "，请调整参数或换一种方式";
                    }
                    metrics.recordToolCall(call.getFunction().getName());
                    toolCount++;
                    Map<String, Object> toolMsg = new java.util.LinkedHashMap<>();
                    toolMsg.put("type", "tool");              // ⚠️ 必须带 type（deepseek-v4-flash）
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", call.getId());
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
            }
            // 超过 maxIterations 仍没出答案 → 降级提示
            metrics.recordQuery(System.currentTimeMillis() - start, iterations, toolCount);
            return "这个问题步骤较多，请拆分成几个小问题再问。";

        } catch (Exception e) {
            log.error("[AgentExecutor] 执行失败: {}", e.getMessage(), e);
            circuitBreaker.onFailure();
            metrics.recordQuery(System.currentTimeMillis() - start, 0, 0);
            return "抱歉，处理你的问题时出了点状况，请稍后重试。";
        }
    }
}
