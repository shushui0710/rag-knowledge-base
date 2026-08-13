package com.liushuwen.rag.rag;

import java.util.List;

/**
 * 回答质量评审服务（阶段4-反思 / Self-Correct）
 *
 * 生成回答后，用一个 LLM 评判"是否直接回答了问题、是否有依据"，
 * 不合格则带评审意见重新生成（最多 criticMaxRetry 次）。
 *
 * 面试考点：
 * - 反思成本：每次多一次 LLM 调用 → 只在必要时启用，或用便宜模型当评审
 * - 防无限循环：重写次数硬上限
 */
public interface CriticService {

    /**
     * 评判回答质量
     *
     * @param question 用户问题
     * @param answer   生成的回答
     * @param sources  检索到的来源片段（用于检查回答是否有依据）
     * @return 评审结果（pass + reason）
     */
    Critique judge(String question, String answer, List<String> sources);
}
