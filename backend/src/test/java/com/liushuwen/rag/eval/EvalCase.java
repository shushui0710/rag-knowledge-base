package com.liushuwen.rag.eval;

import lombok.Data;

/**
 * 检索评估用例 DTO：问题 + 期望关键词，是评估集的最小数据单元。
 * 【设计要点】可量化召回指标：用"Top5 片段是否含期望关键词"判断命中，简单可用、易批量维护
 * 【常见问题】关键词命中会不会误判？——可能漏掉同义表述；可升级为 documentId 维度命中或 LLM 判相关，权衡成本与精度
 */
@Data
public class EvalCase {
    // 功能：测试问题｜要点：评估输入与检索输入一致
    private String question;
    // 功能：期望出现在 Top5 片段中的关键词｜要点：命中判据（片段含词即记一次命中）
    private String expectedKeyword;
}
