package com.liushuwen.rag.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.config.RagProperties;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索链（**全站唯一实现**）：向量化 → 检索层用户隔离 → 长期记忆召回 → 查询改写 → 混合检索 → Rerank 精排 → minScore 过滤 → 拼装上下文/依据。
 *
 * 【设计要点·为什么要有这个类】修复前这条链在三个地方各写了一份，且三份**并不一致**——
 * <pre>
 *   ① ChatServiceImpl.askByRag  ：embed → 隔离 → 记忆 → 改写 → 混合检索 → 重排 → minScore 过滤
 *   ② DocumentAgent.execute     ：embed → 隔离 → 记忆 → 改写 → 混合检索 → 重排 →（漏了 minScore 过滤）
 *   ③ GenerateReportTool.execute：embed → 隔离 →（无记忆/无改写/无重排/无过滤）纯稠密 TopK
 * </pre>
 * 后果有三：改一处（阈值/模板/隔离）必漏另两处；② 会把自己都没通过的候选喂给 LLM；
 * ③ 与"混合检索 + 重排"的选型叙事自相矛盾（报告是检索质量的展示窗口，反而用了最弱的一路）；
 * 而 ① 还有个潜伏的 {@code vectors.get(0)} 空列表越界风险（另外两处都做了空值防御）。
 * 现在三者都委托本类，"检索怎么做"只有一个答案。
 *
 * 【设计要点】本类只负责"取到什么"，不负责"怎么用"：空结果时返回空 Outcome，由调用方决定
 * 是回兜底文案（主链）、返回 Agent 文案（DocumentAgent）还是提示无法生成（报告工具）。
 *
 * 【常见问题】为什么 minScore 过滤放在链内而不是各调用方？——它是"检索质量"的一部分，
 * 三处口径必须一致；放进链内才能保证"没通过阈值的片段绝不进 Prompt"这条不变量只有一处实现。
 * 【常见问题】为什么记忆召回也在链内？——记忆是检索增强的一路来源，与文档片段同属"喂给模型的上下文"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalChain {

    private final EmbeddingService embeddingService;
    private final DocumentMapper documentMapper;
    private final MemoryService memoryService;
    private final QueryRewriterService queryRewriterService;
    private final RerankService rerankService;
    private final MilvusService milvusService;
    private final RagProperties ragProperties;

    /** 参考片段在 Prompt 中的编号前缀（片段标记，三处调用方共用同一写法） */
    public static final String REF_PREFIX = "【参考";

    /** 长期记忆注入 Prompt 时的块标记（与文档片段明确区分） */
    public static final String MEMORY_BLOCK_TAG = "【历史问答记录】";

    /**
     * 一次检索的结果快照。
     *
     * @param results             已过 minScore 阈值的精排片段（可能为空）
     * @param evidence            与 results 一一对应的片段原文（供 sources/评审核对"是否有据"）
     * @param contextBlock        按 {@code 【参考N】} 编号拼好的上下文块（不含长期记忆）
     * @param memories            召回到的长期记忆（Q:/A: 文本，可能为空）
     * @param topScore            results 中的最高分（记忆回存质量门槛用；空结果为 0）
     * @param vectorizationFailed 问题向量化是否失败（与"检索无命中"是两回事，调用方提示语不同）
     */
    public record Outcome(List<MilvusService.SearchResult> results,
                          List<String> evidence,
                          String contextBlock,
                          List<String> memories,
                          float topScore,
                          boolean vectorizationFailed) {

        /** 检索是否没有可用片段（含向量化失败） */
        public boolean isEmpty() {
            return results.isEmpty();
        }

        /** 文档片段 + 长期记忆拼成的完整上下文（供需要记忆增强的调用方使用） */
        public String contextWithMemories() {
            if (memories == null || memories.isEmpty()) {
                return contextBlock;
            }
            return contextBlock + MEMORY_BLOCK_TAG + "\n"
                    + String.join("\n---\n", memories) + "\n\n";
        }
    }

    /** 空结果（检索无命中） */
    private static Outcome empty() {
        return new Outcome(List.of(), List.of(), "", List.of(), 0f, false);
    }

    /** 空结果（问题向量化失败） */
    private static Outcome vectorizationFailed() {
        return new Outcome(List.of(), List.of(), "", List.of(), 0f, true);
    }

    /**
     * 执行一次完整检索。
     *
     * @param question 用户问题（或报告主题）
     * @return 检索结果快照；失败/无命中时 {@link Outcome#isEmpty()} 为 true
     */
    public Outcome retrieve(String question) {
        // 功能：向量化｜要点：查询与文档必须落在同一向量空间，否则相似度无意义
        List<float[]> vectors = embeddingService.embed(List.of(question));
        if (vectors == null || vectors.isEmpty()) {
            log.warn("[RetrievalChain] 问题向量化失败，本次按无召回处理: {}", question);
            return vectorizationFailed();
        }

        // 功能：检索层用户隔离——先查当前用户已向量化文档 ID，作为 Milvus expr 过滤条件｜要点：多租户隔离不能只靠 MySQL 的 eq(userId)，向量库需独立 expr 过滤，否则跨用户向量泄露
        Long userId = UserContext.getUserId();
        List<Long> documentIds = scopeOf(userId);

        // 功能：旁路召回长期记忆（异常内部返回空列表）｜要点：记忆增强是非阻塞旁路，失败降级为空而非中断
        List<String> memories = memoryService.recall(userId, question);

        // 功能：改写 → 混合检索（召回 recallTopK）→ Rerank 精排（保留 rerankTopN）｜要点：各降级点（改写失败用原句、无 bm25 退化纯稠密、Rerank 失败退回原分数）保证链路不中断
        String rewriteQuery = queryRewriterService.rewrite(question);
        List<MilvusService.SearchResult> reranked = rerankService.rerank(question,
                milvusService.hybridSearch(rewriteQuery, vectors.get(0),
                        ragProperties.getRetrieval().getRecallTopK(), documentIds),
                ragProperties.getRetrieval().getRerankTopN());

        // 功能：minScore 阈值过滤（yml 默认 0.35），过滤后为空则由调用方走各自兜底｜要点：COSINE ∈ [-1,1]，中文相似度普遍偏低（0.3~0.5 常见），阈值须用测试集校准；空上下文硬喂 LLM 必幻觉，故宁可兜底
        double minScore = ragProperties.getAgent().getMinScore();
        List<MilvusService.SearchResult> results =
                (reranked == null ? List.<MilvusService.SearchResult>of() : reranked)
                        .stream()
                        .filter(h -> h.getScore() >= minScore)
                        .collect(Collectors.toList());
        if (results.isEmpty()) {
            return empty();
        }

        // 功能：拼装 【参考N】 上下文块与依据列表｜要点：检索片段既进 Prompt 又作为"依据"返回，上层反思评审要核对"是否有据"，必须拿到原始片段
        StringBuilder ctx = new StringBuilder();
        List<String> evidence = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            String content = results.get(i).getContent();
            content = content == null ? "" : content;
            ctx.append(REF_PREFIX).append(i + 1).append("】").append(content).append("\n\n");
            evidence.add(content);
        }

        float topScore = results.stream()
                .map(MilvusService.SearchResult::getScore)
                .max(Float::compare)
                .orElse(0f);
        return new Outcome(results, evidence, ctx.toString(), memories, topScore, false);
    }

    /**
     * 检索范围：当前用户已向量化的文档 ID 列表。
     * 【设计要点】userId 为 null（未登录/定时任务）时返回 null，表示不加 document_id 过滤；
     * 已登录但一篇文档都没向量化时返回**空列表**，Milvus 直接返回空（连一次注定为空的 RPC 都不发）。
     */
    private List<Long> scopeOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId)
                        .eq(Document::getEmbeddingStatus, 1)
                        .select(Document::getId))
                .stream().map(Document::getId).toList();
    }
}
