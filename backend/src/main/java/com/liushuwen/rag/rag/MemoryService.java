package com.liushuwen.rag.rag;

import java.util.List;

/**
 * 长期记忆服务：把问答对按用户隔离存入 qa_memory 向量集合，跨会话召回历史注入上下文。
 * 【设计要点】记忆召回-回存闭环：读时相似度过滤、写时按质量门槛防噪声入库，避免"记忆污染"
 * 【常见问题】为什么不能把所有问答都存？——低质量问答入库会越积越差，需回存质量门槛；常见问题：多用户如何隔离？→ 写入带 user_id，召回 expr 过滤，跨用户互不可见
 */
public interface MemoryService {

    /**
     * 保存一次问答交换到长期记忆（写入带 user_id，便于按用户隔离）。
     *
     * @param userId   当前用户（记忆按用户隔离）
     * @param question 用户问题
     * @param answer   助手回答（截断存储）
     */
    void saveExchange(Long userId, String question, String answer);

    /**
     * 召回与当前问题相关的历史问答（仅当前用户、仅超相似度阈值的 Top 片段）。
     *
     * @param userId   当前用户
     * @param question 当前问题
     * @return 历史问答文本列表（Q:...\nA:...），空列表表示无相关记忆
     */
    List<String> recall(Long userId, String question);
}
