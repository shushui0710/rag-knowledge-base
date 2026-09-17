package com.liushuwen.rag.controller;

import com.liushuwen.rag.agent.AgentExecutor;
import com.liushuwen.rag.agent.OrchestratorAgent;
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

import java.util.List;

/**
 * Agent 智能问答接口：单 Agent（ReAct+工具）与多 Agent 编排两个入口。
 * 【设计要点】编排模式：单 Agent 用 ReAct 循环（思考-行动-观察）调工具；多 Agent 用主管(Orchestrator)分派子任务
 * 【常见问题】为何 /api/** 也走 JWT 拦截？——与现有接口一致，问答须登录；orchestrate 的 history 为何暂传空？——会话历史持久化未完成，先跑通链路
 */
@Tag(name = "Agent 智能问答")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentExecutor agentExecutor;
    private final OrchestratorAgent orchestratorAgent;

    @Data
    public static class AskRequest {
        private String question;
    }

    @Operation(summary = "单 Agent 问答（ReAct + 工具调用）")
    @PostMapping("/ask")
    public Result<String> ask(@RequestBody AskRequest req) {
        // 【缺陷修复·HTTP 语义双轨】原为 return Result.error(400, ...)：方法正常返回会被 Spring
        // 按 HTTP 200 发出，业务码只存在于响应体，与 BusinessException 路径的 HTTP 400 不一致。
        // 统一改抛业务异常，由 GlobalExceptionHandler 收敛为 HTTP 400。
        if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        return Result.success(agentExecutor.execute(req.getQuestion()));
    }

    @Operation(summary = "多 Agent 编排问答")
    @PostMapping("/orchestrate")
    public Result<String> orchestrate(@RequestBody AskRequest req) {
        // 同上：与 /ask 保持一致的 HTTP 语义（缺陷修复·HTTP 语义双轨）
        if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        // 功能：调用多 Agent 编排，历史暂传空列表｜要点：会话历史持久化未完成，先跑通链路，后续接会话表或前端传入
        return Result.success(orchestratorAgent.execute(req.getQuestion(), List.of()));
    }
}
