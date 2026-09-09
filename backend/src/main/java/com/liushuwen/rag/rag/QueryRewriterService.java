package com.liushuwen.rag.rag;

/**
 * 查询改写服务：把口语化提问改写成检索友好的关键词短语，是提升向量召回率的前置环节。
 * 【设计要点】查询改写（Query Rewriting）：口语→关键词，弥合用户表述与文档用词鸿沟，降低召回漏检
 * 【常见问题】改写失败怎么办？——降级返回原句，对主链路完全无感，检索质量不至于更差
 */
public interface QueryRewriterService {

    /**
     * 查询改写：输入口语问题，输出空格分隔的检索词。
     *
     * @param question 用户原始问题（口语）
     * @return 改写后的检索词（空格分隔的关键词短语）
     */
    String rewrite(String question);
}
