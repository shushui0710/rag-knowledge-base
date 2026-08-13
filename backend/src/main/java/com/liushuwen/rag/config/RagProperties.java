package com.liushuwen.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG / Agent 演进配置组（第8周新增）
 *
 * 对应 application.yml 中 rag.* 下的新配置：
 *   rag.agent.*       → Agent 执行器、熔断器参数
 *   rag.retrieval.*   → 检索优化（混合检索、重排）参数
 *
 * 面试考点：
 * - @ConfigurationProperties 与 @Value 的区别：
 *   @Value 适合读单个值；@ConfigurationProperties 适合读一组强类型配置
 * - 松散绑定：yml 里 kebab-case（max-iterations）自动映射到 camelCase 字段（maxIterations）
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** Agent 执行器配置 */
    private Agent agent = new Agent();

    /** 检索优化配置 */
    private Retrieval retrieval = new Retrieval();

    @Data
    public static class Agent {
        /** ReAct 循环最大轮数（防死循环） */
        private int maxIterations = 5;

        /** 检索结果的最低相似度阈值，低于此值不进入 Prompt */
        private double minScore = 0.35;

        /** 熔断阈值：连续失败多少次进入熔断 */
        private int breakerFailureThreshold = 5;

        /** 熔断打开时长（毫秒），期间直接走兜底 */
        private long breakerOpenMillis = 60_000;

        /** 反思重写最大次数（阶段4） */
        private int criticMaxRetry = 1;
    }

    @Data
    public static class Retrieval {
        /** 混合检索权重：alpha * 稠密分 + (1-alpha) * 稀疏分 */
        private double hybridAlpha = 0.7;

        /** 召回条数（重排前） */
        private int recallTopK = 20;

        /** 重排后保留条数（进入 Prompt 的片段数） */
        private int rerankTopN = 5;

        /** Embedding 缓存上限（条），超限清空重建 */
        private int embedCacheLimit = 5000;
    }
}
