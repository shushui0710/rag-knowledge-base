package com.liushuwen.rag.controller;

import com.liushuwen.rag.agent.AgentExecutor;
import com.liushuwen.rag.agent.AgentMetrics;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * Agent 智能问答接口：暴露 ReAct 引擎的**调试入口**（单端点）。
 * 【定位说明·这是调试端点，不是产品入口】用户唯一的 Agent 入口是对话页「深度思考」
 * （POST /api/chat/ask + mode=agent → OrchestratorAgent → ReportAgent → 同一个 AgentExecutor）。
 * 本端点保留的理由是「可独立验证引擎」：它直连 AgentExecutor，不掺意图路由那次 LLM 调用
 * （路由本身要烧一次 LLM，且可能把问题分派到别的分支），因此适合 Knife4j 手工调试与
 * acceptance A5 的隔离取证——A5-12 的三链降级对比、A5-14 的「单引擎两入口」assertSame 都依赖它。
 * 【设计要点】入口层指标记账：/api/agent/ask 不经过 ChatService，故由本类统一记账一次。
 * 记账口径——入口记"次数+端到端耗时"，依赖出口（LlmService/ToolRegistry.execute）记"调用次数"；熔断同样收口在 LlmService。
 * 【已删除·POST /api/agent/orchestrate】该端点与产品入口完全重叠（产品入口 = 同一套 OrchestratorAgent 代码
 * + 多轮历史 + 落库，严格覆盖它），前端（frontend/src）对它的引用为 0 次，独有价值仅"不落库"，
 * 属纯冗余：留着会让"编排到底有几个入口"变得含糊。删除后 OrchestratorAgent.execute(String, List)
 * 失去唯一调用方，已作为死方法一并移除——与当初删 REPORT 死分支同一原则：装配了没人调的东西不留。
 * 【常见问题】为何 /api/** 也走 JWT 拦截？——与现有接口一致，问答须登录
 */
@Tag(name = "Agent 引擎直连接口")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentExecutor agentExecutor;

    /** 指标记账（入口层唯一写者之一，另一个是 ChatServiceImpl.ask） */
    private final AgentMetrics metrics;

    @Data
    public static class AskRequest {
        private String question;
    }

    @Operation(summary = "ReAct 引擎直连（调试用；产品入口在对话页「深度思考」）")
    @PostMapping("/ask")
    public Result<String> ask(@RequestBody AskRequest req) {
        // 【缺陷修复·HTTP 语义双轨】原为 return Result.error(400, ...)：方法正常返回会被 Spring
        // 按 HTTP 200 发出，业务码只存在于响应体，与 BusinessException 路径的 HTTP 400 不一致。
        // 统一改抛业务异常，由 GlobalExceptionHandler 收敛为 HTTP 400。
        if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        return Result.success(timed(() -> agentExecutor.execute(req.getQuestion())));
    }

    /**
     * 入口层计时记账：执行一次问答并按端到端耗时累加问答次数。
     * 【设计要点】记账放"入口"而非"执行器"：执行器会被上层复用，在内部记账会重复；
     * 入口与用户请求一一对应，天然只记一次。放 finally 保证失败请求同样计入流量。
     */
    private <T> T timed(Supplier<T> call) {
        long start = System.currentTimeMillis();
        try {
            return call.get();
        } finally {
            metrics.recordQuery(System.currentTimeMillis() - start);
        }
    }
}
