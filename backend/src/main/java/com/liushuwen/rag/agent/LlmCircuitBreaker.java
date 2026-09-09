package com.liushuwen.rag.agent;

import com.liushuwen.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 熔断器：下游连续失败达阈值后打开熔断，期间直接走兜底，保护 LLM API 不被持续打崩。
 * 【设计要点】熔断状态机：closed→连续失败达阈值(5次)→open 60s→恢复；与限流区别（护下游 vs 护己）
 * 【常见问题】为什么熔断而非一直重试？——重试既烧钱又雪上加霜，熔断给下游喘息、恢复后自动放行
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
