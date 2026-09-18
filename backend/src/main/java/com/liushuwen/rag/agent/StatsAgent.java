package com.liushuwen.rag.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据查询 Agent：回答统计类问题，组合调用统计工具与列表工具，失败返回错误文案不抛异常。
 * 【设计要点】职责聚焦：专用 Agent 只做一类事，Prompt/工具精简，决策准、成本低
 * 【设计要点】工具经 ToolRegistry.execute 统一执行（查表+执行+计数唯一出口），不直接依赖具体 Tool 实现——
 * 修复前本类直接调 statsTool.execute()/listTool.execute()，绕过了埋点：编排链路的工具调用从不计数。
 * 【常见问题】为什么工具调用失败返回文案而非抛异常？——让编排层兜底，避免中断整个问答
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsAgent implements Agent {

    /** 工具执行唯一出口（按名取工具 + 执行 + 计数），同时解除了本类对具体 Tool 类型的硬依赖 */
    private final ToolRegistry toolRegistry;

    /** 工具名常量：与各 Tool 实现的 name() 对应（注册表按名索引） */
    private static final String TOOL_STATS = "query_document_stats";
    private static final String TOOL_LIST = "query_document_list";

    @Override
    public AgentType type() {
        return AgentType.STATS;
    }

    @Override
    public AgentResult execute(String task, List<Map<String, Object>> history) {
        try {
            // 统计 + 列表组合返回（覆盖"有多少/列出哪些"两类问题）
            String stats = toolRegistry.execute(TOOL_STATS, Map.of());
            String list = toolRegistry.execute(TOOL_LIST, Map.of());
            // 证据 = 工具原文：本 Agent 不经过 LLM，输出即数据库聚合事实，可作为自身依据交给上层评审
            return AgentResult.of(stats + "\n\n" + list, List.of(stats, list));
        } catch (Exception e) {
            log.error("数据查询失败: task={}, error={}", task, e.getMessage(), e);
            return AgentResult.of("数据查询失败：" + e.getMessage() + "，请稍后重试。");
        }
    }
}
