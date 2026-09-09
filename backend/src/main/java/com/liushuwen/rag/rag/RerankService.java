package com.liushuwen.rag.rag;

import com.liushuwen.rag.document.service.MilvusService;

import java.util.List;

/**
 * 重排序服务：对粗召回候选做语义精排，是"向量召回→精排"两段式检索的精排段。
 * 【设计要点】两段式检索（召回+重排）：先用向量/全文快速召回 TopK，再用交叉编码器精排提精度
 * 【常见问题】为什么不直接用向量相似度排序？——双塔向量是"query-doc 独立编码"的粗信号，重排用"query+doc 联合编码"捕捉细粒度相关性，排序质量更高
 */
public interface RerankService {

    /**
     * 重排序：输入问题与粗召回候选，输出精排后保留 topN 的片段（分数被重排模型刷新）。
     *
     * @param query      用户问题（Rerank 的输入是「问题 + 候选片段」）
     * @param candidates 粗召回结果
     * @param topN       精排后保留条数
     * @return 精排后的片段（含新分数）
     */
    List<MilvusService.SearchResult> rerank(String query, List<MilvusService.SearchResult> candidates, int topN);
}
