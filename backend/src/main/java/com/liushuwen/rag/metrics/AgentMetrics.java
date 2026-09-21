package com.liushuwen.rag.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标埋点：进程内 ConcurrentHashMap 按天累计问答次数、耗时、LLM/工具调用数，供观测接口查询。
 * 【设计要点】线程安全计数：ConcurrentHashMap + AtomicLong 无锁并发累加，避免 synchronized 开销
 * 【设计要点·唯一写者口径】埋点收敛到「唯一出口」，而不是散落在各调用点——
 *   ① queryCount / avgCostMs：用户问答次数与端到端耗时，由**入口层**记账
 *      （ChatServiceImpl.ask 覆盖对话页两种模式、AgentController 覆盖调试端点 /api/agent/ask），
 *      一次用户提问恰好记一次；
 *   ② llmCalls：由 **LlmService** 记账——它的 3 个方法是全站唯一打 /v1/chat/completions 的出口；
 *   ③ toolCalls：由 **ToolRegistry.execute** 记账——它是全站唯一执行 Tool 的出口。
 *   出口唯一 ⇒ 任何链路（RAG / 单 Agent ReAct / 多 Agent 编排）都不会漏记，也不会重复记。
 * 【曾有缺陷·G-08/G-09】修复前 llmCalls 的写法是"双计"：AgentExecutor 循环内每轮调一次 recordLlmCall()，
 * 结束时 recordQuery(iterations, toolCount) 又按轮数/工具数 addAndGet 补一次，同一次调用被记两遍；
 * 同时埋点只存在于 AgentExecutor 内部，主 RAG 链路（/api/chat/ask）与多 Agent 编排链路（对话页 mode=agent）
 * 全程不计数——指标只反映了 1/3 的流量。现已按上述"唯一出口"口径重构。
 * 历史原因：埋点曾与"谁调用 LLM"耦合在执行器里，而执行器并非唯一调用方，必然既漏又重。
 * 【常见问题】生产环境如何做可观测？——换 Micrometer + Prometheus，指标/日志/链路追踪三件套
 */
@Slf4j
@Component
public class AgentMetrics {

    /** 按日期累计：date -> DayStat */
    private final Map<String, DayStat> stats = new ConcurrentHashMap<>();

    private static String today() {
        return java.time.LocalDate.now().toString();
    }

    /**
     * 记录一次用户问答：问答次数 +1、端到端耗时累加。
     * 【设计要点】只累计"入口口径"的两个量（次数 + 端到端耗时）；LLM/工具调用数由各自的唯一出口单独记账，
     * 因此这里**不再接收** llmCalls/toolCalls 参数——参数一旦存在，调用方就会忍不住把已知数字塞进来，
     * 于是同一个事实被记两遍（这正是修复前的缺陷成因：签名本身诱导了重复计数）。
     *
     * @param costMs 本次问答端到端耗时（由入口层从收到请求计时）
     */
    public void recordQuery(long costMs) {
        DayStat stat = stats.computeIfAbsent(today(), k -> new DayStat());
        stat.queryCount.incrementAndGet();
        stat.totalCostMs.addAndGet(costMs);
    }

    /** 记录一次 LLM 调用（唯一调用方：LlmService —— 全站 LLM 接口的唯一出口） */
    public void recordLlmCall() {
        stats.computeIfAbsent(today(), k -> new DayStat()).llmCalls.incrementAndGet();
    }

    /** 记录一次工具调用（唯一调用方：ToolRegistry.execute —— 全站工具执行的唯一出口） */
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
