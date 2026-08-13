package com.liushuwen.rag.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 指标埋点（阶段5-生产化：观测性）
 *
 * 用进程内 ConcurrentHashMap 按天累计指标，暴露给 MetricsController：
 * - queryCount   : 问答次数
 * - totalCostMs  : 累计耗时（毫秒）
 * - llmCalls     : LLM 调用次数
 * - toolCalls    : 工具调用次数
 *
 * 面试考点：
 * - 为什么用 ConcurrentHashMap + AtomicLong？多线程安全累加，无锁
 * - 生产环境会换成 Micrometer + Prometheus，这里演示最小实现
 * - 可观测性三要素：指标（Metrics）、日志（Log）、链路追踪（Trace）
 *
 * ✅ 本文件即【标准答案】（已完整实现，无需再填）：recordQuery/recordLlmCall/recordToolCall/todaySnapshot 全部可用。
 */
@Slf4j
@Component
public class AgentMetrics {

    /** 按日期累计：date -> DayStat */
    private final Map<String, DayStat> stats = new ConcurrentHashMap<>();

    private static String today() {
        return java.time.LocalDate.now().toString();
    }

    /** 记录一次完整问答 */
    public void recordQuery(long costMs, int llmCalls, int toolCalls) {
        DayStat stat = stats.computeIfAbsent(today(), k -> new DayStat());
        stat.queryCount.incrementAndGet();
        stat.totalCostMs.addAndGet(costMs);
        stat.llmCalls.addAndGet(llmCalls);
        stat.toolCalls.addAndGet(toolCalls);
    }

    /** 记录一次 LLM 调用（AgentExecutor 内部使用） */
    public void recordLlmCall() {
        stats.computeIfAbsent(today(), k -> new DayStat()).llmCalls.incrementAndGet();
    }

    /** 记录一次工具调用 */
    public void recordToolCall(String toolName) {
        stats.computeIfAbsent(today(), k -> new DayStat()).toolCalls.incrementAndGet();
        log.debug("[AgentMetrics] tool call: {}", toolName);
    }

    /** 今日指标快照（供 MetricsController 返回） */
    public Map<String, Object> todaySnapshot() {
        DayStat s = stats.computeIfAbsent(today(), k -> new DayStat());
        long count = s.queryCount.get();
        return Map.of(
                "date", today(),
                "queryCount", count,
                "avgCostMs", count == 0 ? 0 : s.totalCostMs.get() / count,
                "llmCalls", s.llmCalls.get(),
                "toolCalls", s.toolCalls.get()
        );
    }

    /** 单日统计（线程安全计数器） */
    public static class DayStat {
        private final AtomicLong queryCount = new AtomicLong();
        private final AtomicLong totalCostMs = new AtomicLong();
        private final AtomicLong llmCalls = new AtomicLong();
        private final AtomicLong toolCalls = new AtomicLong();
    }
}
