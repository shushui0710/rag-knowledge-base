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
 * 指标观测接口：暴露今日 Agent 运行指标（问答量/平均耗时/LLM 与工具调用次数）。
 * 【设计要点】可观测性：指标 + 日志 + 链路追踪是生产化三件套，能回答"今天处理多少问答、调几次 LLM"
 * 【常见问题】为何做成接口而非埋点上报？——演示项目直接快照返回，真实环境应推 Prometheus / Grafana 由服务端拉取
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
