package com.liushuwen.rag.rag;

import java.util.List;

/**
 * 回答质量评审服务：LLM-as-Judge 反思环节，对生成回答做"是否切题、是否有据"的评判。
 * 【设计要点】反思/自纠错（Self-Correct）：用独立 LLM 当评委，不合格则带意见重写；重写次数硬上限防无限循环
 * 【常见问题】每次评审多一次 LLM 调用值不值？——只在质量敏感场景启用，可用便宜小模型当评委控成本；常见问题：上限为何取 1 次？→ 边际收益递减且成本线性涨
 */
public interface CriticService {

    /**
     * 评判回答质量：输入问题、回答与检索来源，输出 pass + 不合格原因。
     *
     * @param question 用户问题
     * @param answer   生成的回答
     * @param sources  检索到的来源片段（用于检查回答是否有依据）
     * @return 评审结果（pass + reason）
     */
    Critique judge(String question, String answer, List<String> sources);
}
