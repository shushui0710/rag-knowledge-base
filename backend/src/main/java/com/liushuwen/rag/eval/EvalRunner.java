package com.liushuwen.rag.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索评估入口：用外置测试集衡量检索策略质量，是"评估驱动迭代"的工程化抓手。
 * 【设计要点】评估驱动迭代：先有可量化指标（Top5 命中率）再谈优化，避免检索调参沦为玄学
 * 【常见问题】为什么用外置 JSON 用例而非写死？——用例与代码解耦，产品/运营可增删，且能进 CI 做回归；常见问题：命中指标还能换什么？→ 可用 documentId 维度命中、MRR、NDCG 等
 */
public class EvalRunner {

    // 功能：单条检索评估用例（问题 + 期望命中文档）｜要点：测试数据与逻辑分离，便于批量维护
    @Data
    public static class EvalCase {
        private String question;      // 测试问题
        private Long expectedDocId;   // 期望命中的文档ID
    }

    /**
     * 评估主流程：从外置用例集加载问题，逐条向量化检索 Top5，统计期望文档命中率。
     * 【设计要点】Top5 命中率指标：衡量"期望文档是否进入候选"，直接反映检索策略好不好，是迭代的北极星
     * 【常见问题】为什么是 Top5 不是 Top1？——RAG 取多片段喂模型，只要目标进 TopK 即算召回成功，Top5 比 Top1 更贴合真实管线
     */
    public static void main(String[] args) {
        // 占位主流程：完整评估逻辑见 src/test 下的 @SpringBootTest 实现（EvalRunnerTest）
        // 功能：加载用例→逐条向量化检索 Top5→统计期望文档命中率｜要点：单条失败不中断整体评估
        List<EvalCase> cases = new ArrayList<>();
        System.out.println("评估用例数: " + cases.size() + "（待填充 docs/eval/questions.json）");
        System.out.println("Top5 命中率: 待实现");
    }
}
