package com.liushuwen.rag.rag;

import java.util.List;

/**
 * 长期记忆服务（阶段4）
 *
 * 把"高质量问答对"向量化存进 Milvus（qa_memory collection），
 * 新问题进来先检索历史问答，命中则作为记忆注入 Prompt。
 *
 * 面试考点：
 * - 为什么需要长期记忆？跨会话复用，让 Agent "记得"上次答过什么
 * - 记忆污染的防治：入库前质量筛选 + 召回分数阈值 + 时间衰减
 */
public interface MemoryService {

    /**
     * 保存一次问答交换到长期记忆
     *
     * @param question 用户问题
     * @param answer   助手回答（截断存储）
     */
    void saveExchange(String question, String answer);

    /**
     * 召回与当前问题相关的历史问答
     *
     * @param question 当前问题
     * @return 历史问答文本列表（Q:...\nA:...），空列表表示无相关记忆
     */
    List<String> recall(String question);
}
