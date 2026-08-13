package com.liushuwen.rag.rag;

import com.liushuwen.rag.document.service.MilvusService;

import java.util.List;

/**
 * 重排序服务（阶段2）
 *
 * 粗召回（recallTopK=20）→ Rerank 精排 → 取 rerankTopN=5 进入 Prompt。
 * 实现见 RerankServiceImpl（当前为骨架：按原始分数排序取前 N）。
 */
public interface RerankService {

    /**
     * 重排序
     *
     * @param query      用户问题（Rerank 的输入是「问题 + 候选片段」）
     * @param candidates 粗召回结果
     * @param topN       精排后保留条数
     * @return 精排后的片段（含新分数）
     */
    List<MilvusService.SearchResult> rerank(String query, List<MilvusService.SearchResult> candidates, int topN);
}
