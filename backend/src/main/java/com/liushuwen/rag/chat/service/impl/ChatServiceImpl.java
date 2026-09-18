package com.liushuwen.rag.chat.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.LlmUnavailableException;
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
import com.liushuwen.rag.agent.AgentMetrics;
import com.liushuwen.rag.agent.AgentResult;
import com.liushuwen.rag.agent.OrchestratorAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.liushuwen.rag.config.RagProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 在线问答链路编排服务（核心难点类）。
 * 在整条链路中处于"召回 → 精排 → 生成"的串联中枢，把检索、记忆、模型能力编排成一次问答。
 * 【设计要点】RAG 在线链路编排：每环节为何这样排布（检索前先改写、精排后再过滤、生成前先做会话归属校验）
 * 【常见问题】各环节如何降级？——改写失败用原句、检索/记忆异常静默、Rerank 失败退回原分数、LLM 熔断时返回统一兜底文案；saveExchange 为何设分数门槛？——检索最高分 ≥ 0.6 才存长期记忆，防低质回答污染记忆库
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
    /**
     * 多 Agent 编排（意图路由 + 子 Agent 分派 + 反思评审）。
     * 【设计要点】对话页「深度思考」模式走这条链路：为什么复用 chat 入口而不是另开一个引擎直连端点？
     * 因为引擎直连端点不落库——回答不进 chat_message 表，会话历史里看不到、刷新即丢，也无法复用会话归属与标题逻辑。
     * （原先并存的 /api/agent/orchestrate 就是这样一个直连端点，已被删除。）
     */
    private final OrchestratorAgent orchestratorAgent;

    /**
     * 指标记账（入口层唯一写者之一，另一个是 AgentController）。
     * 【设计要点·指标口径】queryCount/avgCostMs 记在"用户入口"，llmCalls/toolCalls 记在"依赖出口"
     * （LlmService / ToolRegistry.execute）。入口与用户请求一一对应 ⇒ 天然只记一次，
     * 不会像"记在执行器里"那样因执行器被上层复用而重复计数。详见 AgentMetrics 的「唯一写者口径」。
     */
    private final AgentMetrics metrics;

    /** 记忆入库质量门槛（0.6）：检索最高分 ≥ 该值才存问答对，防低质/兜底回答污染长期记忆库 */
    private static final float MEMORY_SAVE_MIN_SCORE = 0.6f;

    /** agent 模式注入的历史条数上限：只取最近 N 条消息，控制 token */
    private static final int AGENT_HISTORY_LIMIT = 10;

    /** 「深度思考」模式的请求参数值 */
    private static final String MODE_AGENT = "agent";

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
     * 问答入口（默认 RAG 链路）。保留双参签名，供既有调用方与集成用例使用。
     */
    @Override
    public ChatMessage ask(Long sessionId, String question) {
        return ask(sessionId, question, null);
    }

    /**
     * 带链路模式的问答入口，是对话页「深度思考」开关的落地点。
     * 【设计要点】用 mode 字段区分链路而不是新增端点：落库、会话历史、标题更新、用户隔离这套逻辑
     * 全部在 ChatServiceImpl 内且已按会话/用户校验，复用同一入口可让两条链路共享这些约束；
     * 前端只需多传一个字段，既有 RAG 用例（不传 mode）行为完全不变。
     * 【常见问题】mode 传错会怎样？——非法值静默回退 RAG 链路，不抛异常：前端拼错一个字段不应把问答打挂
     * 【常见问题】指标为什么在这里记？——本方法是"用户提问"在对话链路上的唯一入口（两种模式共用），
     * 在此记账刚好一次；此前在 AgentExecutor 内记账的写法既漏掉 RAG 链路，又因执行器被编排复用而可能重复
     *
     * @param mode "agent" = 多 Agent 编排（意图路由 + 子 Agent + 反思评审）；其余/空 = 默认 RAG 链路
     */
    @Override
    public ChatMessage ask(Long sessionId, String question, String mode) {
        // 功能：入口层记账（问答次数 + 端到端耗时）｜要点：放 try/finally 里——链路异常（如检索/LLM 失败）时
        // 本次提问同样已被受理，也应统计到，否则指标会低估真实流量
        long start = System.currentTimeMillis();
        try {
            if (MODE_AGENT.equalsIgnoreCase(mode == null ? null : mode.trim())) {
                return askByAgent(sessionId, question);
            }
            return askByRag(sessionId, question);
        } finally {
            metrics.recordQuery(System.currentTimeMillis() - start);
        }
    }

    /**
     * 默认 RAG 链路：落库提问 → 向量化 → 检索层用户隔离 → 长期记忆召回 → 查询改写 + 混合检索(召回20) → Rerank 精排(5) → minScore 0.35 过滤 → Prompt 拼接 → LLM 生成 → 存 sources/回答/记忆。
     * 【设计要点】RAG 在线链路编排与逐环节降级：改写失败用原句、检索/记忆异常静默、Rerank 失败退回原分数、空召回走兜底；会话归属校验防越权
     * 【常见问题】为什么先落库提问再走检索？——即使后续检索/LLM 失败，提问记录也保留，便于排查与续聊；如何防 A 用户检索到 B 用户向量？——Milvus expr 按当前用户文档 ID 列表过滤
     */
    private ChatMessage askByRag(Long sessionId, String question) {
        log.info("问答请求[RAG] - 会话:{}, 问题:{}", sessionId, question);

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
        // 【缺陷修复·不可变集合】RerankService 与 MilvusService.hybridSearch 在"无候选/用户无文档"时
        // 返回的是 List.of()（JDK 不可变集合），在其上调用 removeIf 会抛 UnsupportedOperationException。
        // 该路径恰是新用户"未上传文档即提问"的常态链路，修复前必现 HTTP 500。改用 stream().filter() 生成
        // 新的可变列表，既是防御性编程（不假设上游返回可变集合），也保持过滤语义不变。
        results = results.stream()
                .filter(h -> h.getScore() >= minScore)
                .collect(Collectors.toList());
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
        // 【熔断降级】熔断打开时 LlmService（唯一出口）抛 LlmUnavailableException。本链路不能因此把
        // 用户请求打成 500：改为返回统一的"服务暂不可用"兜底回答，检索到的依据照常落库可追溯；
        // 并且这条兜底回答不进长期记忆（degraded 标志），避免降级文案以"高质量问答"身份污染记忆库。
        String answer;
        boolean degraded = false;
        try {
            answer = llmService.chat(prompt);
        } catch (LlmUnavailableException e) {
            log.warn("LLM 熔断中，主问答链返回兜底文案: {}", question);
            answer = LlmUnavailableException.FALLBACK_MESSAGE;
            degraded = true;
        }

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
        // 熔断降级产生的兜底文案虽然检索分可能够高，但内容不含任何知识——不得入库（degraded 拦截）
        float topScore = results.stream()
                .map(MilvusService.SearchResult::getScore)
                .max(Float::compare).orElse(0f);
        if (!degraded && topScore >= MEMORY_SAVE_MIN_SCORE) {
            memoryService.saveExchange(userId, question, answer);
        }


        return assistantMsg;
    }

    /**
     * 多 Agent 编排链路（对话页「深度思考」）：取历史 → 编排生成（带依据）→ 回答与依据一并落库。
     * 【设计要点】与 RAG 链路共享同一套落库与隔离约束，回答照常进 chat_message，刷新后历史可回读
     * 【常见问题】为什么这里不写长期记忆？——DocumentAgent 内部已按同一质量门槛（topScore≥0.6）回存，
     * 在这里再存一次会产生重复记忆条目
     * 【常见问题】为什么依据要落库？——agent 的回答来自检索片段或工具原文，把依据随回答存下来，
     * 前端「参考来源」才有内容可展示，答案才可追溯（与 RAG 链路 sources 的目的一致）
     */
    private ChatMessage askByAgent(Long sessionId, String question) {
        log.info("问答请求[Agent] - 会话:{}, 问题:{}", sessionId, question);

        // 功能：先取历史再落库提问｜要点：顺序不能反——先落提问会把本轮问题也读进历史，导致 Prompt 里问题出现两次
        List<Map<String, Object>> history = recentHistory(sessionId);

        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(question);
        chatMessageMapper.insert(userMsg);

        AgentResult result = orchestratorAgent.executeResult(question, history);

        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(result.answer());
        assistantMsg.setSources(evidenceToSources(result.evidence()));
        chatMessageMapper.insert(assistantMsg);
        return assistantMsg;
    }

    /**
     * 读取会话最近 N 条消息，转成 Agent 侧约定的 history 结构（按时间正序）。
     * 【设计要点】只取最近 AGENT_HISTORY_LIMIT 条：多轮上下文要控制 token，越早的轮次对当前提问价值越低
     */
    private List<Map<String, Object>> recentHistory(Long sessionId) {
        List<ChatMessage> all = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreateTime));
        List<Map<String, Object>> history = new ArrayList<>();
        int from = Math.max(0, all.size() - AGENT_HISTORY_LIMIT);
        for (int i = from; i < all.size(); i++) {
            ChatMessage m = all.get(i);
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "message");
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            history.add(item);
        }
        return history;
    }

    /**
     * 把 Agent 依据片段转成前端 sources 结构。
     * 【设计要点】agent 的依据来自检索片段或工具原文，没有相似度分数，故只给 content 并标 type=evidence，
     * 不伪造 score（前端据 type 决定渲染「相似度」标签还是「依据片段」标签）
     */
    private String evidenceToSources(List<String> evidence) {
        JSONArray array = new JSONArray();
        if (evidence != null) {
            for (String e : evidence) {
                if (e == null || e.isBlank()) {
                    continue;
                }
                JSONObject source = new JSONObject();
                source.put("content", e.length() > 100 ? e.substring(0, 100) + "..." : e);
                source.put("type", "evidence");
                array.add(source);
            }
        }
        return array.toJSONString();
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
