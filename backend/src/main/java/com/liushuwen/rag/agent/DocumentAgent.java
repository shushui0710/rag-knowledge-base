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

import java.util.ArrayList;
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

    /** 注入 Prompt 的历史条数上限：只取最近 N 条，控制 token 与噪声 */
    private static final int HISTORY_MAX_TURNS = 6;

    /** 单条历史内容的截断长度：历史里可能整段塞过很长的回答，超长会挤掉参考资料 */
    private static final int HISTORY_ITEM_MAX_CHARS = 200;

    @Override
    public AgentType type() {
        return AgentType.DOCUMENT;
    }

    @Override
    public AgentResult execute(String task, List<Map<String, Object>> history) {
        // 功能：本轮 history 多轮记忆 + 跨会话长期记忆 recall（按用户隔离）｜要点：双入口记忆设计
        // 常见问题：为什么分两轮历史？→ 当前轮多轮上下文 + 跨会话长期记忆，召回注入 Prompt 增强连贯性
        List<float[]> vectors = embeddingService.embed(List.of(task));
        if (vectors == null || vectors.isEmpty()) {
            return AgentResult.of("文档向量化失败，请稍后重试。");
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
            return AgentResult.of("未在知识库中找到相关文档，请换个问法或先上传相关文档。");
        }

        // 功能：检索片段既拼进 Prompt 又作为"依据"随结果返回｜要点：上层反思评审要核对"是否有据"，必须拿到原始片段
        StringBuilder ctx = new StringBuilder();
        List<String> evidence = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            String content = results.get(i).getContent();
            content = content == null ? "" : content;
            ctx.append("【参考").append(i + 1).append("】").append(content).append("\n\n");
            evidence.add(content);
        }
        // 长期记忆注入（标注为历史问答记录）
        if (memories != null && !memories.isEmpty()) {
            ctx.append("【历史问答记录】\n")
                    .append(String.join("\n---\n", memories)).append("\n\n");
        }
        String prompt = buildHistoryBlock(history)
                + "请根据以下参考资料回答用户问题：\n\n" + ctx
                + "用户问题：" + task;
        String answer = llmService.chat(prompt);

        // 高质量问答对回存长期记忆（topScore≥门槛才存，与 ChatServiceImpl 同一质量线）
        float topScore = results.stream()
                .map(MilvusService.SearchResult::getScore)
                .max(Float::compare).orElse(0f);
        if (topScore >= MEMORY_SAVE_MIN_SCORE) {
            memoryService.saveExchange(userId, task, answer);
        }
        return AgentResult.of(answer, evidence);
    }

    /**
     * 把会话历史拼成 Prompt 前缀块，让多轮追问能指代前文（如"那第二篇呢"）。
     * 【设计要点】history 为空时返回空串，Prompt 与单轮完全一致——保证不改变无历史调用的既有行为
     * 【常见问题】为什么不用 LlmService 的多轮 messages 接口？——本 Agent 的 Prompt 是"资料 + 问题"模板，
     * 历史作为一段可读上下文注入最简单直观；真要严格多轮，应改造 LlmService 支持 system/user/assistant 消息列表
     *
     * @param history 会话历史（按时间正序），元素形如 {"role":"user"/"assistant","content":"..."}
     * @return Prompt 前缀（无历史时为空串）
     */
    private String buildHistoryBlock(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        int from = Math.max(0, history.size() - HISTORY_MAX_TURNS);
        for (int i = from; i < history.size(); i++) {
            Map<String, Object> msg = history.get(i);
            if (msg == null || msg.get("content") == null) {
                continue;
            }
            String text = String.valueOf(msg.get("content")).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > HISTORY_ITEM_MAX_CHARS) {
                text = text.substring(0, HISTORY_ITEM_MAX_CHARS) + "...";
            }
            block.append("assistant".equals(msg.get("role")) ? "助手：" : "用户：")
                    .append(text).append("\n");
        }
        return block.length() == 0 ? "" : "【历史对话】\n" + block + "\n";
    }
}
