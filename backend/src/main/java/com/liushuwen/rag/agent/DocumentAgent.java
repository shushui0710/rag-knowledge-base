package com.liushuwen.rag.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.config.RagProperties;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.rag.MemoryService;
import com.liushuwen.rag.rag.QueryRewriterService;
import com.liushuwen.rag.rag.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文档问答 Agent：专注 RAG 检索问答，复用查询改写→混合检索→Rerank 检索链，并接入长期记忆。
 * 【设计要点】检索增强生成（RAG）：改写降歧 + 混合检索召回 + Rerank 精排，提升答案相关性
 * 【常见问题】长期记忆如何回存？——topScore≥0.6 的高质量问答对回存，跨会话按用户隔离召回
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentAgent implements Agent {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final LlmService llmService;
    /** 查询改写 + Rerank（与 ChatServiceImpl 检索链一致，复用成熟链路） */
    private final QueryRewriterService queryRewriterService;
    private final RerankService rerankService;
    private final RagProperties ragProperties;
    /** 检索层用户隔离 */
    private final DocumentMapper documentMapper;
    /** 长期记忆：跨会话召回与回存 */
    private final MemoryService memoryService;

    /** 记忆入库质量门槛（与 ChatServiceImpl 保持一致） */
    private static final float MEMORY_SAVE_MIN_SCORE = 0.6f;

    @Override
    public AgentType type() {
        return AgentType.DOCUMENT;
    }

    @Override
    public String execute(String task, List<Map<String, Object>> history) {
        // 功能：本轮 history 多轮记忆 + 跨会话长期记忆 recall（按用户隔离）｜要点：双入口记忆设计
        // 常见问题：为什么分两轮历史？→ 当前轮多轮上下文 + 跨会话长期记忆，召回注入 Prompt 增强连贯性
        List<float[]> vectors = embeddingService.embed(List.of(task));
        if (vectors == null || vectors.isEmpty()) {
            return "文档向量化失败，请稍后重试。";
        }

        // 检索层用户隔离：只在当前用户已向量化文档内检索
        Long userId = UserContext.getUserId();
        List<Long> documentIds = null;
        if (userId != null) {
            documentIds = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                            .eq(Document::getUserId, userId)
                            .eq(Document::getEmbeddingStatus, 1)
                            .select(Document::getId))
                    .stream().map(Document::getId).toList();
        }

        // 长期记忆召回（旁路设计，失败返回空列表，不影响主链路）
        List<String> memories = memoryService.recall(userId, task);

        String rewriteQuery = queryRewriterService.rewrite(task);
        int recallTopK = ragProperties.getRetrieval().getRecallTopK();
        int rerankTopN = ragProperties.getRetrieval().getRerankTopN();
        List<MilvusService.SearchResult> results = rerankService.rerank(task,
                milvusService.hybridSearch(rewriteQuery, vectors.get(0), recallTopK, documentIds),
                rerankTopN);
        if (results == null || results.isEmpty()) {
            return "未在知识库中找到相关文档，请换个问法或先上传相关文档。";
        }

        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            String content = results.get(i).getContent();
            ctx.append("【参考").append(i + 1).append("】")
                    .append(content == null ? "" : content).append("\n\n");
        }
        // 长期记忆注入（标注为历史问答记录）
        if (memories != null && !memories.isEmpty()) {
            ctx.append("【历史问答记录】\n")
                    .append(String.join("\n---\n", memories)).append("\n\n");
        }
        String prompt = "请根据以下参考资料回答用户问题：\n\n" + ctx
                + "用户问题：" + task;
        String answer = llmService.chat(prompt);

        // 高质量问答对回存长期记忆（topScore≥门槛才存，与 ChatServiceImpl 同一质量线）
        float topScore = results.stream()
                .map(MilvusService.SearchResult::getScore)
                .max(Float::compare).orElse(0f);
        if (topScore >= MEMORY_SAVE_MIN_SCORE) {
            memoryService.saveExchange(userId, task, answer);
        }
        return answer;
    }
}
