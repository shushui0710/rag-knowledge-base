package com.liushuwen.rag.controller;

import com.liushuwen.rag.agent.AgentExecutor;
import com.liushuwen.rag.agent.OrchestratorAgent;
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
 * Agent 智能问答接口（阶段3/4 演示入口）
 *
 * - /api/agent/ask          : 单 Agent（ReAct + 工具调用）
 * - /api/agent/orchestrate  : 多 Agent 编排（主管分派）
 *
 * 说明：/api/** 已被 JwtInterceptor 拦截，需要登录携带 token（与现有接口一致）。
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
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            return Result.error(400, "问题不能为空");
        }
        return Result.success(agentExecutor.execute(req.getQuestion()));
    }

    @Operation(summary = "多 Agent 编排问答")
    @PostMapping("/orchestrate")
    public Result<String> orchestrate(@RequestBody AskRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            return Result.error(400, "问题不能为空");
        }
        // 历史留空（骨架）；填充后可从前端传历史或从会话表加载
        return Result.success(orchestratorAgent.execute(req.getQuestion(), List.of()));
    }
}
