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
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.rag.QueryRewriterService;
import com.liushuwen.rag.rag.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.liushuwen.rag.config.RagProperties;

import java.util.List;

/**
 * 智能问答服务实现 - RAG在线流程的核心
 *
 * 在线流程：用户提问 → 向量检索 → 拼接Prompt → 大模型生成 → 答案+来源
 *
 * 跨模块调用说明：
 * - EmbeddingService（document模块）：把用户问题转成向量
 * - MilvusService（document模块）：向量搜索相关文档块
 * - LlmService（chat模块）：调用DeepSeek生成回答
 *
 * 面试考点：RAG在线流程编排 — 为什么是这个顺序，每一步的作用
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
    /** 阶段2：查询改写（口语 → 检索词） */
    private final QueryRewriterService queryRewriterService;
    /** 阶段2：Rerank 精排（召回 → 精排） */
    private final RerankService rerankService;

    @Value("${rag.top-k}")
    private int topK;

    @Value("${rag.prompt-template}")
    private String promptTemplate;

    @Override
    public ChatSession createSession() {
        ChatSession session = new ChatSession();
        session.setTitle("新对话");
        // ============================================================
        // TODO 6（⭐ 难度）：设置当前用户ID
        //
        // 当前代码：session 没有设置 userId（userId 为 null）
        // 应该改为：从 UserContext 获取当前登录用户的ID
        //
        // 提示：
        //   session.setUserId(UserContext.getUserId());
        //
        // 面试考点：
        //   - 会话必须关联用户，否则无法实现数据隔离
        // ============================================================
        // TODO 6: 在这里添加 session.setUserId(UserContext.getUserId());
        session.setUserId(UserContext.getUserId());
        chatSessionMapper.insert(session);
        return session;
    }

    @Override
    public List<ChatSession> listSessions() {
        // ============================================================
        // TODO 7（⭐ 难度）：按当前用户ID过滤会话列表
        //
        // 当前代码：return chatSessionMapper.selectList(null);  ← 查所有人的会话！
        // 应该改为：用 LambdaQueryWrapper 按 userId 过滤
        //
        // 提示：
        //   LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        //   wrapper.eq(ChatSession::getUserId, UserContext.getUserId())
        //          .orderByDesc(ChatSession::getUpdateTime);
        //   return chatSessionMapper.selectList(wrapper);
        //
        // 面试考点：
        //   - 为什么按 updateTime 排序？最近更新的会话排最前
        // ============================================================
        LambdaQueryWrapper<ChatSession> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId,UserContext.getUserId())
                .orderByDesc(ChatSession::getUpdateTime);

        return chatSessionMapper.selectList(wrapper);  // TODO 7: 替换为按 userId 过滤
    }

    @Override
    public ChatMessage ask(Long sessionId, String question) {
        log.info("问答请求 - 会话:{}, 问题:{}", sessionId, question);

        // ============================================================
        // TODO 3（⭐ 难度）：保存用户问题到chat_message
        //
        // 提示：
        //   ChatMessage userMsg = new ChatMessage();
        //   userMsg.setSessionId(sessionId);
        //   userMsg.setRole("user");
        //   userMsg.setContent(question);
        //   chatMessageMapper.insert(userMsg);
        //
        // 面试考点：为什么要先存用户问题？
        //   即使后续流程失败，用户的问题记录也保留了
        // ============================================================
        ChatMessage userMsg=new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(question);
        chatMessageMapper.insert(userMsg);



        // ============================================================
        // TODO 4（⭐⭐ 难度）：将问题向量化 + 检索Top-K
        //
        // 步骤1：调用 embeddingService.embed(List.of(question)) 得到向量列表
        // 步骤2：取第一个向量（因为只有一条文本）：vectors.get(0)
        // 步骤3：调用 milvusService.search(queryVector, topK) 检索
        //
        // 提示：
        //   List<float[]> vectors = embeddingService.embed(List.of(question));
        //   float[] queryVector = vectors.get(0);
        //   List<MilvusService.SearchResult> results = milvusService.search(queryVector, topK);
        //
        // 面试考点：为什么用户问题也要向量化？
        //   Milvus是向量搜索，查询向量和存储向量在同一空间才能比较相似度
        // ============================================================
        List<float[]> vectors=embeddingService.embed(List.of(question));
        float[] queryVector = vectors.get(0);

        // ============================================================
        // 阶段2 检索链（✅ 已实现）：
        //   查询改写 → 混合检索（稠密+BM25稀疏，召回 recallTopK=20）→ Rerank 精排 → topN=5
        //
        // 说明：
        // - 改写结果只用于"检索"，回答 Prompt 仍用原问题（queryRewriterService 内部已兜底）
        // - 混合检索：collection 未重建（无 bm25_vector）时自动降级纯稠密（hybridSearch 内部）
        // - Rerank：API 失败自动降级按原分数排序（rerankService 内部）
        // - 参数从 rag.retrieval.* 读（RagProperties）
        // ============================================================
        String rewriteQuery = queryRewriterService.rewrite(question);
        int recallTopK = ragProperties.getRetrieval().getRecallTopK();
        int rerankTopN = ragProperties.getRetrieval().getRerankTopN();
        List<MilvusService.SearchResult> results = rerankService.rerank(question,
                milvusService.hybridSearch(rewriteQuery, queryVector, recallTopK),
                rerankTopN);

        // ============================================================
        // TODO 1-2（⭐ 难度）：score 阈值过滤（低分片段不进 Prompt，防幻觉+省钱）
        //
        // 【标准答案】完整实现（可直接插入下面这 5 行）
        //
        // 前置：给 ChatServiceImpl 增加注入（@RequiredArgsConstructor 自动构造注入）：
        //   private final RagProperties ragProperties;
        //   // import com.liushuwen.rag.config.RagProperties;
        //
        // 插入代码（在"拼接上下文"之前）：
        //   double minScore = ragProperties.getAgent().getMinScore();   // yml 默认 0.35
        //   results.removeIf(h -> h.getScore() < minScore);
        //   if (results.isEmpty()) {
        //       ChatMessage fallback = new ChatMessage();
        //       fallback.setSessionId(sessionId);
        //       fallback.setRole("assistant");
        //       fallback.setContent("知识库中没有找到足够相关的内容，请换个问法或先上传相关文档。");
        //       chatMessageMapper.insert(fallback);
        //       return fallback;
        //   }
        //
        // 面试考点：
        // - COSINE 分数 ∈ [-1,1]，中文语义相似度普遍偏低（0.3~0.5 常见），
        //   阈值要拿你的测试集校准，不要拍脑袋
        // - 过滤后为空 → 走兜底文案，而不是让 LLM 硬编
        // ============================================================
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



        // ============================================================
        // TODO 5（⭐⭐ 难度）：拼接上下文 + 构建Prompt
        //
        // 步骤1：把检索到的文本块拼接成一个字符串（用编号格式）
        //   StringBuilder sb = new StringBuilder();
        //   for (int i = 0; i < results.size(); i++) {
        //       sb.append("【参考").append(i + 1).append("】")
        //         .append(results.get(i).getContent()).append("\n\n");
        //   }
        //   String context = sb.toString();
        //
        // 步骤2：用promptTemplate拼接最终prompt
        //   promptTemplate 里有 {context} 和 {question} 两个占位符
        //   String prompt = promptTemplate.replace("{context}", context)
        //                                 .replace("{question}", question);
        //
        // 面试考点：Prompt工程 — 给大模型明确的上下文和指令，防止幻觉
        // ============================================================
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append("【参考").append(i + 1).append("】")
                    .append(results.get(i).getContent()).append("\n\n");
        }
        String context = sb.toString();
        String prompt = promptTemplate.replace("{context}", context)
                .replace("{question}", question);



        // ============================================================
        // TODO 6（⭐⭐ 难度）：调用大模型 + 构建来源 + 保存回答 + 返回
        //
        // 步骤1：调用 llmService.chat(prompt) 得到回答
        //   String answer = llmService.chat(prompt);
        //
        // 步骤2：构建sources JSON（用FastJSON，和MilvusService一致）
        //   JSONArray sourcesArray = new JSONArray();
        //   for (MilvusService.SearchResult sr : results) {
        //       JSONObject source = new JSONObject();
        //       source.put("chunkId", sr.getChunkId());
        //       source.put("score", sr.getScore());
        //       String preview = sr.getContent().length() > 100
        //           ? sr.getContent().substring(0, 100) + "..."
        //           : sr.getContent();
        //       source.put("content", preview);
        //       sourcesArray.add(source);
        //   }
        //   String sources = sourcesArray.toJSONString();
        //
        // 步骤3：保存助手回答到chat_message
        //   ChatMessage assistantMsg = new ChatMessage();
        //   assistantMsg.setSessionId(sessionId);
        //   assistantMsg.setRole("assistant");
        //   assistantMsg.setContent(answer);
        //   assistantMsg.setSources(sources);
        //   chatMessageMapper.insert(assistantMsg);
        //
        // 步骤4：返回助手消息
        //   return assistantMsg;
        //
        // 面试考点：
        // - 为什么要存sources？—— 可追溯性，用户知道答案从哪来的
        // - 为什么用FastJSON不用Jackson？—— Milvus SDK依赖FastJSON，项目统一用
        // ============================================================
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


        return assistantMsg;



        
    }

    @Override
    public List<ChatMessage> getHistory(Long sessionId) {
        // ============================================================
        // TODO 7（⭐ 难度）：按sessionId查询历史消息
        //
        // 当前代码：chatMessageMapper.selectList(null)  ← 查所有会话的消息！
        // 应该改为：用LambdaQueryWrapper按sessionId过滤，按createTime排序
        //
        // 提示：
        //   LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        //   wrapper.eq(ChatMessage::getSessionId, sessionId)
        //          .orderByAsc(ChatMessage::getCreateTime);
        //   return chatMessageMapper.selectList(wrapper);
        //
        // 面试考点：LambdaQueryWrapper条件查询 — MyBatis-Plus的核心API
        // ============================================================
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreateTime);
        return chatMessageMapper.selectList(wrapper);
    }

    @Override
    public void deleteSession(Long sessionId) {
        // ============================================================
        // TODO 8（⭐ 难度）：级联删除 — 先删消息，再删会话
        //
        // 当前代码：chatMessageMapper.delete(null)  ← 删所有会话的消息！
        // 应该改为：
        //   1. 先按sessionId删除该会话的所有消息
        //   2. 再删除会话本身（逻辑删除）
        //
        // 提示：
        //   LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        //   wrapper.eq(ChatMessage::getSessionId, sessionId);
        //   chatMessageMapper.delete(wrapper);
        //   chatSessionMapper.deleteById(sessionId);
        //
        // 面试考点：级联删除顺序 — 先删子表（消息）再删父表（会话），
        //          否则会留下孤儿记录
        // ============================================================
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
