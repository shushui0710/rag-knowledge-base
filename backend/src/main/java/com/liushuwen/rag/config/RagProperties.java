package com.liushuwen.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG / Agent 配置组：绑定 rag.* 下 agent 与 retrieval 两组参数。
 * 【设计要点】@ConfigurationProperties vs @Value：前者整体绑定强类型配置组、支持松散绑定与校验，后者只适合单个值
 * 【常见问题】松散绑定是什么？——yml kebab-case（max-iterations）自动映射到 camelCase 字段（maxIterations）；嵌套组如何映射？——内部静态类对应 yml 缩进层级
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
        private int maxIterations = 5;          // ReAct 循环最大轮数，防死循环
        private double minScore = 0.35;         // 检索结果最低相似度阈值，低于不进 Prompt
        private int breakerFailureThreshold = 5; // 熔断阈值：连续失败次数进熔断
        private long breakerOpenMillis = 60_000; // 熔断打开时长（ms），期间走兜底
        private int criticMaxRetry = 1;         // 反思重写最大次数
    }

    @Data
    public static class Retrieval {
        private double hybridAlpha = 0.7;      // 混合检索权重：alpha*稠密分 + (1-alpha)*稀疏分
        private int recallTopK = 20;           // 召回条数（重排前）
        private int rerankTopN = 5;            // 重排后保留条数（进 Prompt 的片段数）
        private int embedCacheLimit = 5000;    // Embedding 缓存上限（条），超限清空重建
    }
}
