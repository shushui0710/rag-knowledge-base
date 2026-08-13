package com.liushuwen.rag.agent;

import com.liushuwen.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 熔断器（阶段5-生产化：容错）
 *
 * 连续失败达到阈值后"熔断"一段时间，期间不再调用 LLM，直接走兜底回答。
 *
 * 为什么熔断而不是一直重试？
 * - 持续重试浪费钱（每次失败也可能计费）且可能雪上加霜
 * - 熔断给外部服务喘息时间，恢复后自动放行
 *
 * 面试考点：
 * - 熔断三态：关闭（正常）→ 打开（拒绝）→ 半开（试探恢复）
 * - 这里是简化实现（关闭/打开两态），可扩展半开态
 * - 与 RateLimiter（限流）的区别：限流保护自己，熔断保护下游
 *
 * ✅ 本文件即【标准答案】（已完整实现，无需再填）：tryAcquire/onSuccess/onFailure 三态闭环可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCircuitBreaker {

    private final RagProperties ragProperties;

    /** 连续失败次数 */
    private final AtomicInteger failureCount = new AtomicInteger();

    /** 熔断打开截止时间（毫秒时间戳） */
    private volatile long openUntil = 0;

    /** 当前是否允许调用 LLM */
    public boolean tryAcquire() {
        if (System.currentTimeMillis() < openUntil) {
            log.warn("[熔断] LLM 熔断中，剩余 {}ms，直接走兜底", openUntil - System.currentTimeMillis());
            return false;
        }
        return true;
    }

    /** 调用成功：清零失败计数 */
    public void onSuccess() {
        failureCount.set(0);
    }

    /** 调用失败：计数 +1，达到阈值则打开熔断 */
    public void onFailure() {
        int threshold = ragProperties.getAgent().getBreakerFailureThreshold();
        if (failureCount.incrementAndGet() >= threshold) {
            openUntil = System.currentTimeMillis() + ragProperties.getAgent().getBreakerOpenMillis();
            failureCount.set(0);
            log.error("[熔断] 连续失败 {} 次，熔断 {}ms", threshold, ragProperties.getAgent().getBreakerOpenMillis());
        }
    }
}
