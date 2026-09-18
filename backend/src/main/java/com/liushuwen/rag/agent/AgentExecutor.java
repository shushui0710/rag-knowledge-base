package com.liushuwen.rag.agent;

import com.alibaba.fastjson.JSONObject;
import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.common.LlmUnavailableException;
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
 * 【设计要点】本类只负责"编排循环"，不承担任何"跨界记账"：LLM 计数与熔断都在 LlmService（唯一出口）、
 * 工具计数在 ToolRegistry.execute、问答次数/耗时在入口层（AgentController / ChatServiceImpl.ask）。
 * 修复前本类既记 LLM 数、又按轮数补记、还自己持有一个熔断器——三件事都是"挂错层"：
 * 挂在被上层复用的执行器上，计数会重复、熔断只覆盖路过它的链路。
 * 【常见问题】为什么要轮数上限？——防 LLM 重复调用死循环、控制成本与延迟；超限降级返回提示
 * 【常见问题】工具调用失败为何不抛异常？——回填错误文案让 LLM 自我修正，而非中断循环
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutor {

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final RagProperties ragProperties;

    /** 单条工具输出作为"证据"入库时的截断长度：报告类工具输出很长，全量回传会让 sources 字段膨胀 */
    private static final int EVIDENCE_MAX_CHARS = 500;

    /** 注入 ReAct 循环的历史条数上限：只取最近 N 条，控制 token */
    private static final int HISTORY_MAX_TURNS = 6;

    /** 历史单条内容截断长度 */
    private static final int HISTORY_ITEM_MAX_CHARS = 200;

    /** 熔断兜底文案：与 LlmUnavailableException 的契约文案一致 */
    private static final String UNAVAILABLE_TEXT = LlmUnavailableException.FALLBACK_MESSAGE;

    /** 异常兜底文案 */
    private static final String ERROR_TEXT = "抱歉，处理你的问题时出了点状况，请稍后重试。";

    /** 超轮降级文案 */
    private static final String OVER_ITERATIONS_TEXT = "这个问题步骤较多，请拆分成几个小问题再问。";

    /**
     * 执行一次 Agent 问答（只取回答文本）。
     * 【设计要点】保留此签名供 AgentController 等"只要文本"的调用方使用；需要落库带依据时用 executeResult
     *
     * @param userQuestion 用户问题
     * @return 最终回答（或降级/兜底文案）
     */
    public String execute(String userQuestion) {
        return executeResult(userQuestion, List.of()).answer();
    }

    /**
     * 执行一次 Agent 问答（回答 + 依据 + 多轮历史）。
     * 【设计要点】ReportAgent（编排路由的 REPORT 分支）复用本方法 ⇒ 单 Agent 的 ReAct 能力成为编排链路的一条分支，
     * 而不是"只有裸接口 /api/agent/ask 才够得着"的孤岛。
     * 【设计要点】evidence 收集循环内每次工具执行的输出（截断）：这些输出就是回答的事实依据，
     * 上层据此落库 sources 并供 CriticService 核对"有没有知识库依据"。
     * 【已删除·executeResult(String) 单参重载】该重载（无历史，返回 AgentResult）在 main 与 test 中均无调用方，
     * 属死方法。随裸接口 /api/agent/orchestrate 的删除一并清理——"装配了没人调"的方法与端点同罪。
     *
     * @param userQuestion 用户问题
     * @param history      会话历史（按时间正序，元素形如 {"type":"message","role":"user"/"assistant","content":"..."}）
     * @return AgentResult（answer 非空；evidence 可能为空，表示整轮未调用任何工具）
     */
    public AgentResult executeResult(String userQuestion, List<Map<String, Object>> history) {
        // 功能：证据收集——循环内每次工具输出都作为"依据"存下来｜要点：不暴露可变状态，随返回值传递，天然线程安全
        List<String> evidence = new ArrayList<>();
        try {
            // 功能：ReAct 循环（思考→行动→观察→再思考）｜要点：maxIterations 上限防 LLM 死循环
            // 常见问题：为什么需要轮数上限？→ 防止重复调用工具死循环，控制成本与延迟
            List<Map<String, Object>> messages = new ArrayList<>();
            // 功能：多轮历史作为前置对话注入｜要点：role/type 与 OpenAI 兼容协议一致；
            // 只取最近 HISTORY_MAX_TURNS 条并截断内容，避免历史挤掉本轮任务描述
            appendHistory(messages, history);
            // 功能：每条消息带 type 字段（message/tool）｜要点：兼容 OpenAI 的 type 必填，漏填返回 400
            // 常见问题：tool 消息为何要 tool_call_id？→ 必须匹配前置 assistant 的 tool_calls，否则 400
            Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
            userMsg.put("type", "message");
            userMsg.put("role", "user");
            userMsg.put("content", userQuestion);
            messages.add(userMsg);
            int iterations = 0;

            while (iterations++ < ragProperties.getAgent().getMaxIterations()) {
                // 功能：调 LLM 决策（返回 ANSWER 或 TOOL_CALL）｜要点：本类不记 LLM 计数、不判熔断——LlmService 是唯一出口，在那里收口才不漏不重
                LlmService.LlmResponse resp = llmService.chatWithTools(messages, toolRegistry.all());

                // LLM 认为可以回答了 → 直接返回（随回答带上本轮工具证据）
                if (resp.isAnswer()) {
                    return AgentResult.of(resp.getContent(), evidence);
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
                    // 功能：把工具输出收作证据（截断防膨胀）｜要点：报告类工具输出可达数千字，全量回传会让 sources 膨胀
                    if (result != null && !result.isBlank()) {
                        evidence.add(result.length() > EVIDENCE_MAX_CHARS
                                ? result.substring(0, EVIDENCE_MAX_CHARS) + "..." : result);
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
            return AgentResult.of(OVER_ITERATIONS_TEXT, evidence);

        } catch (LlmUnavailableException e) {
            // 熔断打开（LlmService 抛出）：本次没有可用 LLM，返回统一降级文案，用户永不见堆栈
            log.warn("[AgentExecutor] LLM 熔断中，返回降级文案: {}", userQuestion);
            return AgentResult.of(UNAVAILABLE_TEXT);
        } catch (Exception e) {
            log.error("[AgentExecutor] 执行失败: {}", e.getMessage(), e);
            return AgentResult.of(ERROR_TEXT);
        }
    }

    /**
     * 把会话历史转成 ReAct 循环可用的前置消息。
     * 【设计要点】只取最近 N 条并截断内容（历史里可能整段塞过很长的回答），保证不挤掉本轮任务描述
     */
    private void appendHistory(List<Map<String, Object>> messages, List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        int from = Math.max(0, history.size() - HISTORY_MAX_TURNS);
        for (int i = from; i < history.size(); i++) {
            Map<String, Object> item = history.get(i);
            if (item == null || item.get("content") == null) {
                continue;
            }
            String text = String.valueOf(item.get("content")).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > HISTORY_ITEM_MAX_CHARS) {
                text = text.substring(0, HISTORY_ITEM_MAX_CHARS) + "...";
            }
            Map<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("type", "message");
            msg.put("role", item.get("role") == null ? "user" : item.get("role"));
            msg.put("content", text);
            messages.add(msg);
        }
    }
}
