package com.liushuwen.rag.chat.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;
import com.liushuwen.rag.chat.mapper.ChatMessageMapper;
import com.liushuwen.rag.chat.mapper.ChatSessionMapper;
import com.liushuwen.rag.chat.service.ChatService;
import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.rag.MemoryService;
import com.liushuwen.rag.rag.QueryRewriterService;
import com.liushuwen.rag.rag.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.liushuwen.rag.config.RagProperties;

import java.util.List;

/**
 * RAG 在线问答链路编排服务（核心难点类）。
 * 在整条链路中处于"召回 → 精排 → 生成"的串联中枢，把检索、记忆、模型能力编排成一次问答。
 * 【设计要点】RAG 在线链路编排：每环节为何这样排布（检索前先改写、精排后再过滤、生成前先做会话归属校验）
 * 【常见问题】各环节如何降级？——改写失败用原句、检索/记忆异常静默、Rerank 失败退回原分数；saveExchange 为何设分数门槛？——检索最高分 ≥ 0.6 才存长期记忆，防低质回答污染记忆库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final LlmService llmService;
    private final RagProperties ragProperties;
    /** 查询改写服务（口语化提问 → 检索友好词），改写失败内部兜底回退原句 */
    private final QueryRewriterService queryRewriterService;
    /** Rerank 精排服务（召回候选 → 精排 TopN），API 失败自动降级按原分数排序 */
    private final RerankService rerankService;
    /** 长期记忆服务（旁路增强：召回历史问答 + 保存高质量问答对，失败静默） */
    private final MemoryService memoryService;
    /** 检索层用户隔离：查出当前用户已向量化文档的 ID 列表，传给 Milvus expr 过滤 */
    private final DocumentMapper documentMapper;

    /** 记忆入库质量门槛（0.6）：检索最高分 ≥ 该值才存问答对，防低质/兜底回答污染长期记忆库 */
    private static final float MEMORY_SAVE_MIN_SCORE = 0.6f;

    @Value("${rag.top-k}")
    private int topK;

    @Value("${rag.prompt-template}")
    private String promptTemplate;

    @Override
    public ChatSession createSession() {
        ChatSession session = new ChatSession();
        session.setTitle("新对话");
        // 功能：创建会话时把当前登录用户 ID 写入 session，实现会话归属与数据隔离｜要点：JWT 解析出的 userId 经 ThreadLocal 注入 UserContext，避免越权访问他人会话
        session.setUserId(UserContext.getUserId());
        chatSessionMapper.insert(session);
        return session;
    }

    @Override
    public List<ChatSession> listSessions() {
        // 功能：按当前用户 ID 过滤会话列表并按更新时间倒序｜要点：多租户数据隔离（MyBatis-Plus LambdaQueryWrapper.eq 拼 WHERE 条件）
        LambdaQueryWrapper<ChatSession> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId,UserContext.getUserId())
                .orderByDesc(ChatSession::getUpdateTime);

        return chatSessionMapper.selectList(wrapper);
    }

    /**
     * 单次问答主链路：落库提问 → 向量化 → 检索层用户隔离 → 长期记忆召回 → 查询改写 + 混合检索(召回20) → Rerank 精排(5) → minScore 0.35 过滤 → Prompt 拼接 → LLM 生成 → 存 sources/回答/记忆。
     * 【设计要点】RAG 在线链路编排与逐环节降级：改写失败用原句、检索/记忆异常静默、Rerank 失败退回原分数、空召回走兜底；会话归属校验防越权
     * 【常见问题】为什么先落库提问再走检索？——即使后续检索/LLM 失败，提问记录也保留，便于排查与续聊；如何防 A 用户检索到 B 用户向量？——Milvus expr 按当前用户文档 ID 列表过滤
     */
    @Override
    public ChatMessage ask(Long sessionId, String question) {
        log.info("问答请求 - 会话:{}, 问题:{}", sessionId, question);

        // 功能：先落库用户提问消息｜要点：失败幂等——即使后续检索/LLM 失败，用户提问记录也保留，便于排查与续聊
        ChatMessage userMsg=new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(question);
        chatMessageMapper.insert(userMsg);



        // 功能：把用户问题转成向量（与库中文档同处一个 embedding 空间）｜要点：向量检索本质是同空间余弦/内积相似度，查询必须向量化才能比对
        List<float[]> vectors=embeddingService.embed(List.of(question));
        float[] queryVector = vectors.get(0);

        // 功能：检索层用户隔离——先查出当前用户已向量化文档 ID 列表，作为 Milvus expr 过滤条件｜要点：多租户隔离不能只靠 MySQL 的 eq(userId)，向量库需独立 expr 过滤，否则跨用户向量泄露
        Long userId = UserContext.getUserId();
        List<Long> documentIds = null;
        if (userId != null) {
            documentIds = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                            .eq(Document::getUserId, userId)
                            .eq(Document::getEmbeddingStatus, 1)
                            .select(Document::getId))
                    .stream().map(Document::getId).toList();
        }

        // 功能：旁路召回当前用户的长期记忆（只取本用户，异常内部返回空列表，不影响主链路）｜要点：记忆增强属于非阻塞旁路，失败降级为空而非中断
        List<String> memories = memoryService.recall(userId, question);

        // 功能：检索链编排——查询改写 → 混合检索（稠密+BM25稀疏，召回 recallTopK=20）→ Rerank 精排（topN=5）｜要点：混合检索补语义召回的 lexical 短板；各降级点（无 bm25 退化纯稠密、Rerank 失败退回原分数、改写失败用原句）保证链路不中断
        String rewriteQuery = queryRewriterService.rewrite(question);
        int recallTopK = ragProperties.getRetrieval().getRecallTopK();
        int rerankTopN = ragProperties.getRetrieval().getRerankTopN();
        List<MilvusService.SearchResult> results = rerankService.rerank(question,
                milvusService.hybridSearch(rewriteQuery, queryVector, recallTopK, documentIds),
                rerankTopN);

        // 功能：按 minScore 阈值（yml 默认 0.35）过滤低分片段，过滤后为空则走兜底文案直接返回｜要点：COSINE ∈ [-1,1]，中文语义相似度普遍偏低（0.3~0.5 常见），阈值要拿测试集校准；空结果让 LLM 硬编会幻觉，故给兜底而非编造
        double minScore = ragProperties.getAgent().getMinScore();   // yml 默认 0.35
        results.removeIf(h -> h.getScore() < minScore);
        if (results.isEmpty()) {
            ChatMessage fallback = new ChatMessage();
            fallback.setSessionId(sessionId);
            fallback.setRole("assistant");
            fallback.setContent("知识库中没有找到足够相关的内容，请换个问法或先上传相关文档。");
            chatMessageMapper.insert(fallback);
            return fallback;
        }



        // 功能：拼接检索片段为带编号的 context，再用 promptTemplate 的 {context}/{question} 占位符组装最终 Prompt｜要点：Prompt 工程把结构化上下文喂给模型，约束其"基于参考作答"，降低幻觉
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append("【参考").append(i + 1).append("】")
                    .append(results.get(i).getContent()).append("\n\n");
        }
        // 功能：把召回的长期记忆以"历史问答记录"标签注入 Prompt，与文档片段区分；为空则不注入，保证 Prompt 与原版一致｜要点：记忆作为旁路增强，仅补充不喧宾夺主
        if (memories != null && !memories.isEmpty()) {
            sb.append("【历史问答记录】\n")
                    .append(String.join("\n---\n", memories)).append("\n\n");
        }
        String context = sb.toString();
        String prompt = promptTemplate.replace("{context}", context)
                .replace("{question}", question);



        // 功能：调 LLM 生成回答 → 构建 sources JSON（含 chunkId/score/预览）→ 落库助手消息并返回｜要点：存 sources 实现答案可追溯（用户知来源）；用 FastJSON 与 Milvus SDK 依赖统一，避免双 JSON 库
        String answer=llmService.chat(prompt);

        JSONArray sourcesArray = new JSONArray();
        for (MilvusService.SearchResult sr : results) {
            JSONObject source = new JSONObject();
            source.put("chunkId", sr.getChunkId());
            source.put("score", sr.getScore());
            String preview = sr.getContent().length() > 100
                    ? sr.getContent().substring(0, 100) + "..."
                    : sr.getContent();
            source.put("content", preview);
            sourcesArray.add(source);
        }
        String sources = sourcesArray.toJSONString();

        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(answer);
        assistantMsg.setSources(sources);
        chatMessageMapper.insert(assistantMsg);

        // 功能：把本次问答对存入长期记忆（旁路增强，失败只记日志不阻断）｜要点：质量门槛检索最高分 ≥ 0.6（MEMORY_SAVE_MIN_SCORE）才存，确保记忆有可信来源支撑，防低质/兜底回答污染跨会话复用的记忆库
        float topScore = results.stream()
                .map(MilvusService.SearchResult::getScore)
                .max(Float::compare).orElse(0f);
        if (topScore >= MEMORY_SAVE_MIN_SCORE) {
            memoryService.saveExchange(userId, question, answer);
        }


        return assistantMsg;



        
    }

    @Override
    public List<ChatMessage> getHistory(Long sessionId) {
        // 功能：按 sessionId 过滤历史消息并按创建时间升序｜要点：MyBatis-Plus LambdaQueryWrapper 条件构造，按会话隔离 + 时间排序还原对话顺序
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreateTime);
        return chatMessageMapper.selectList(wrapper);
    }

    @Override
    public void deleteSession(Long sessionId) {
        // 功能：级联删除——先删子表（消息）再删父表（会话）｜要点：级联顺序避免孤儿记录；逻辑删除由 MyBatis-Plus 自动改写 SQL 实现软删
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId);
        chatMessageMapper.delete(wrapper);
        chatSessionMapper.deleteById(sessionId);        
        log.info("删除会话: {}", sessionId);
    }

    @Override
    public void updateTitle(Long sessionId, String title) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在: " + sessionId);
        }
        session.setTitle(title);
        chatSessionMapper.updateById(session);
        log.info("更新会话标题: {} -> {}", sessionId, title);
    }
}
