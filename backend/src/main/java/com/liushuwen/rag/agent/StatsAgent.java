package com.liushuwen.rag.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据查询 Agent（阶段4 ✅ 已实现）
 *
 * 职责：回答"有多少文档/哪些文档/各分类情况"等统计类问题。
 * 直接调用数据类工具（统计 + 列表），失败返回错误文案（不抛异常）。
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
