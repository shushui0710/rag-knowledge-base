package com.liushuwen.rag.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据查询 Agent：回答统计类问题，组合调用统计工具与列表工具，失败返回错误文案不抛异常。
 * 【设计要点】职责聚焦：专用 Agent 只做一类事，Prompt/工具精简，决策准、成本低
 * 【常见问题】为什么工具调用失败返回文案而非抛异常？——让上层编排兜底，避免中断整个问答
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsAgent implements Agent {

    private final QueryDocumentStatsTool statsTool;
    private final QueryDocumentListTool listTool;

    @Override
    public AgentType type() {
        return AgentType.STATS;
    }

    @Override
    public String execute(String task, List<Map<String, Object>> history) {
        try {
            // 统计 + 列表组合返回（覆盖"有多少/列出哪些"两类问题）
            String stats = statsTool.execute(Map.of());
            String list = listTool.execute(Map.of());
            return stats + "\n\n" + list;
        } catch (Exception e) {
            log.error("数据查询失败: task={}, error={}", task, e.getMessage(), e);
            return "数据查询失败：" + e.getMessage() + "，请稍后重试。";
        }
    }
}
