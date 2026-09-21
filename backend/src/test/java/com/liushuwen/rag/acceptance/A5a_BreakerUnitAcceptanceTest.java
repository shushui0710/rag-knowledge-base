package com.liushuwen.rag.acceptance;

import com.liushuwen.rag.agent.AgentExecutor;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.config.RagProperties;
import com.liushuwen.rag.llm.LlmCircuitBreaker;
import com.liushuwen.rag.llm.LlmService;
import com.liushuwen.rag.llm.LlmUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5a —— 熔断器本体与接线（A5-01 / A5-02）。
 *
 * 【拆分说明】A5 原为 816 行单文件，按「变更原因」拆成 6 个类：本类只回答一个问题——
 *   <b>熔断器的状态机对不对、它到底挂在哪一层</b>。
 *   改动 LlmCircuitBreaker 或 LlmService 的接线时，只看这一个文件即可判断影响面。
 *
 * 【本类固化的两个结论】
 *   1) 状态机：连续 5 次失败 → 打开；成功一次 → 失败计数清零。（A5-01）
 *   2) 接线：熔断器挂在 <b>全站 LLM 唯一出口 LlmService</b>，AgentExecutor 不再自持。（A5-02）
 *      修复前它被 AgentExecutor 持有 ⇒ 只有 /api/agent/ask 被保护，主问答链 /api/chat/ask 完全裸奔；
 *      因此本类同时是"熔断收口"这一改动的回归护栏。
 */
@DisplayName("A5a 熔断器本体与接线（A5-01~02）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A5a_BreakerUnitAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private LlmService llmService;

    @Autowired
    private LlmCircuitBreaker breakerSingleton;

    @Autowired
    private AgentExecutor agentExecutor;

    @Test
    @Order(1)
    @DisplayName("A5-01 熔断器：连续失败达阈值打开熔断，成功调用后计数清零")
    void a501_circuit_breaker_state_machine() {
        // 直接 new 一个独立实例：避免把运行中的单例熔断掉导致后续 Agent 用例被阻断 60 秒
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(ragProperties);
        int threshold = ragProperties.getAgent().getBreakerFailureThreshold();
        long openMillis = ragProperties.getAgent().getBreakerOpenMillis();

        assertEquals(5, threshold, "熔断阈值应与配置一致（rag.agent.breaker-failure-threshold）");
        assertEquals(60000L, openMillis, "熔断时长应与配置一致（rag.agent.breaker-open-millis）");
        assertTrue(breaker.tryAcquire(), "初始状态应放行");

        for (int i = 1; i < threshold; i++) {
            breaker.onFailure();
            assertTrue(breaker.tryAcquire(), "第 " + i + " 次失败未达阈值（" + threshold + "），应仍放行");
        }
        breaker.onFailure();   // 第 threshold 次
        assertFalse(breaker.tryAcquire(),
                "连续失败达到阈值 " + threshold + " 次后应打开熔断，期间直接走兜底不调 LLM");

        // 成功调用应把失败计数清零：新实例上验证"计数复位"语义
        LlmCircuitBreaker fresh = new LlmCircuitBreaker(ragProperties);
        fresh.onFailure();
        fresh.onFailure();
        fresh.onSuccess();
        for (int i = 1; i < threshold; i++) {
            fresh.onFailure();
            assertTrue(fresh.tryAcquire(),
                    "onSuccess 应清零失败计数，此后需重新累计 " + threshold + " 次才熔断");
        }
        step("A5-01 通过：熔断阈值 " + threshold + " 次 / 时长 " + openMillis
                + "ms，成功后计数清零语义正确");
    }

    @Test
    @Order(2)
    @DisplayName("A5-02 熔断收口到唯一出口：熔断器挂在 LlmService、执行器不再自持，三个出口方法统一被守卫")
    void a502_breaker_is_wired_at_unique_llm_exit() throws Exception {
        // ---- ① 结构断言：熔断器只被"全站 LLM 唯一出口"持有 ----
        assertTrue(A5Support.hasFieldOfType(LlmService.class, LlmCircuitBreaker.class),
                "熔断器应挂在 LlmService（全站 LLM 唯一出口）上；挂在某一层执行器上时，"
                        + "只有路过该执行器的链路被保护，主问答链 /api/chat/ask 会完全裸奔");
        assertFalse(A5Support.hasFieldOfType(AgentExecutor.class, LlmCircuitBreaker.class),
                "修复后 AgentExecutor 不应再自持熔断器（「熔断挂错层」正是它只覆盖单条链路、主链路裸奔的根因）");

        // ---- ② 文案与异常体系契约 ----
        assertEquals("抱歉，AI 服务暂时不可用，请稍后再试。", LlmUnavailableException.FALLBACK_MESSAGE,
                "熔断兜底文案由专属异常统一定义，各链路复用同一口径");
        assertTrue(BusinessException.class.isAssignableFrom(LlmUnavailableException.class),
                "LlmUnavailableException 应继承 BusinessException，复用统一异常体系（未被捕获时 → HTTP 400）");

        // ---- ③ 行为断言：把运行中的单例熔断器强制打开，三个出口方法必须统一抛专属异常 ----
        // 之所以直接改运行中的单例：只有真实链路上那一个熔断器被打开，才能证明"出口被守卫"；
        // 而 new 一个本地实例只能证明状态机算法，证明不了接线。测试结束必须复位（见 finally）。
        long backup = A5Support.forceOpenBreaker(breakerSingleton);
        try {
            assertThrows(LlmUnavailableException.class, () -> llmService.chat("任意问题"),
                    "chat（主问答链出口）在熔断期应直接抛专属异常，不发出请求");
            assertThrows(LlmUnavailableException.class, () -> llmService.chatWithSystem("sys", "user", 0.1),
                    "chatWithSystem（意图路由/查询改写/反思评审的出口）应同样被守卫");
            assertThrows(LlmUnavailableException.class, () -> llmService.chatWithTools(List.of(), List.of()),
                    "chatWithTools（ReAct 的出口）应同样被守卫");

            // ---- ④ 执行器降级文案：熔断期返回固定文案而非把异常抛给用户 ----
            String degraded = agentExecutor.execute("现在知识库里有几个文档？");
            assertEquals(LlmUnavailableException.FALLBACK_MESSAGE, degraded,
                    "熔断期 AgentExecutor 应返回统一兜底文案（用户永不见堆栈）");
        } finally {
            A5Support.restoreBreaker(breakerSingleton, backup);
        }

        // ---- ⑤ 复位后重新放行：熔断是"临时"状态，窗口过后必须自动恢复 ----
        assertTrue(breakerSingleton.tryAcquire(),
                "复位后熔断器应重新放行（否则后续用例会被永久阻断 60s）");
        step("A5-02 通过：熔断器挂在唯一出口 LlmService（AgentExecutor 不再自持），"
                + "chat/chatWithSystem/chatWithTools 三出口统一抛 LlmUnavailableException，复位后放行");
    }
}
