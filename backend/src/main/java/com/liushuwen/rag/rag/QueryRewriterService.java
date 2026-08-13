package com.liushuwen.rag.rag;

/**
 * 查询改写服务（阶段2）
 *
 * 把口语问题改写成更适合检索的关键词/短语，提升召回。
 * 实现见 QueryRewriterServiceImpl（当前为骨架：返回原问题）。
 */
public interface QueryRewriterService {

    /**
     * 查询改写
     *
     * @param question 用户原始问题（口语）
     * @return 改写后的检索词（空格分隔的关键词短语）
     */
    String rewrite(String question);
}
