package com.liushuwen.rag.agent;

import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.config.RagProperties;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.rag.QueryRewriterService;
import com.liushuwen.rag.rag.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文档问答 Agent（阶段4）——专注 RAG 检索问答
 *
 * 已接入阶段2检索链：查询改写 → 混合检索 → Rerank（与 ChatServiceImpl 一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentAgent implements Agent {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final LlmService llmService;
    /** 阶段2：查询改写 + Rerank（与 ChatServiceImpl 检索链一致） */
    private final QueryRewriterService queryRewriterService;
    private final RerankService rerankService;
    private final RagProperties ragProperties;

    @Override
    public AgentType type() {
        return AgentType.DOCUMENT;
    }

    @Override
    public String execute(String task, List<Map<String, Object>> history) {
        // 参数说明：history 是会话历史（多轮记忆），当前骨架未使用；
        // 填充时可将最近几轮注入 Prompt，或接入 MemoryService.recall()
        // TODO 4-1（⭐⭐⭐）：文档问答 Agent
        // 步骤3：拼接 Prompt（含来源引用）
        // 步骤4：调 LlmService 生成
        //
        // 阶段2 检索链（✅ 已实现）：改写 → 混合检索 → Rerank，与 ChatServiceImpl 一致
        List<float[]> vectors = embeddingService.embed(List.of(task));
        if (vectors == null || vectors.isEmpty()) {
            return "文档向量化失败，请稍后重试。";
        }
        String rewriteQuery = queryRewriterService.rewrite(task);
        int recallTopK = ragProperties.getRetrieval().getRecallTopK();
        int rerankTopN = ragProperties.getRetrieval().getRerankTopN();
        List<MilvusService.SearchResult> results = rerankService.rerank(task,
                milvusService.hybridSearch(rewriteQuery, vectors.get(0), recallTopK),
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
        String prompt = "请根据以下参考资料回答用户问题：\n\n" + ctx
                + "用户问题：" + task;
        return llmService.chat(prompt);
    }
}
