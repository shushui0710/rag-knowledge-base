package com.liushuwen.rag.controller;

import com.liushuwen.rag.agent.AgentMetrics;
import com.liushuwen.rag.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 指标接口（阶段5-生产化：观测性）
 *
 * GET /api/metrics/today → 今日问答量、平均耗时、LLM 调用次数、工具调用次数
 *
 * 面试考点：可观测性——面试时演示"今天处理了多少问答、调了几次 LLM"，
 *          说明你有生产思维（指标 + 日志 + 链路追踪）。
 */
@Tag(name = "指标观测")
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final AgentMetrics agentMetrics;

    @Operation(summary = "今日 Agent 指标")
    @GetMapping("/today")
    public Result<Map<String, Object>> today() {
        return Result.success(agentMetrics.todaySnapshot());
    }
}
