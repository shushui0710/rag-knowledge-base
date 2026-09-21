package com.liushuwen.rag.chat.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.liushuwen.rag.agent.AgentResult;
import com.liushuwen.rag.agent.OrchestratorAgent;
import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;
import com.liushuwen.rag.chat.service.ChatService;
import com.liushuwen.rag.chat.service.ChatSessionService;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.llm.LlmService;
import com.liushuwen.rag.llm.LlmUnavailableException;
import com.liushuwen.rag.metrics.AgentMetrics;
import com.liushuwen.rag.rag.MemoryService;
import com.liushuwen.rag.rag.RetrievalChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG 在线问答链路编排服务（核心难点类）。
 * 在整条链路中处于"召回 → 精排 → 生成"的串联中枢，把检索、记忆、模型能力编排成一次问答。
 * 【职责边界】会话与消息的增删改查（含归属校验）已下沉到 ChatSessionService，本类只保留"提问 → 召回 → 生成 → 落库"的编排
 * 【设计要点】RAG 在线链路编排：每环节为何这样排布（检索前先改写、精排后再过滤、生成前先做会话归属校验）
 * 【常见问题】各环节如何降级？——改写失败用原句、检索/记忆异常静默、Rerank 失败退回原分数、LLM 熔断时返回统一兜底文案；saveExchange 为何设分数门槛？——检索最高分 ≥ 0.6 才存长期记忆，防低质回答污染记忆库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    /** 会话/消息的唯一读写出口（含归属校验） */
    private final ChatSessionService chatSessionService;
    private final LlmService llmService;
    /** 长期记忆服务（旁路增强：把高质量问答对回存，失败静默）；**召回侧已并入 RetrievalChain** */
    private final MemoryService memoryService;
    /**
     * 全站唯一检索链（向量化 → 检索层隔离 → 记忆召回 → 查询改写 → 混合检索 → Rerank → minScore 过滤）。
     * 【设计要点】本链路、DocumentAgent、GenerateReportTool 曾各写一份检索代码且口径互不一致；
     * 现统一委托本类 —— "检索怎么做"只允许有一个答案。
     */
    private final RetrievalChain retrievalChain;
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

    /** agent 模式注入的历史条数上限：只取最近 N 条消息，控制 token */
    private static final int AGENT_HISTORY_LIMIT = 10;

    /** 「深度思考」模式的请求参数值 */
    private static final String MODE_AGENT = "agent";

    @Value("${rag.prompt-template}")
    private String promptTemplate;

    @Override
    public ChatSession createSession() {
        return chatSessionService.createSession();
    }

    @Override
    public List<ChatSession> listSessions() {
        return chatSessionService.listSessions();
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
        chatSessionService.appendMessage(sessionId, "user", question, null);



        // 功能：委托全站唯一检索链（向量化 → 检索层隔离 → 记忆召回 → 查询改写 → 混合检索 → Rerank → minScore 过滤）｜要点：检索口径只此一处实现，避免主链/Agent 链/报告工具三份复制悄悄分叉
        RetrievalChain.Outcome outcome = retrievalChain.retrieve(question);

        // 功能：空召回兜底——返回固定拒答文案，**不调用 LLM**｜要点：空上下文硬喂模型几乎必然幻觉，宁可直接拒答；此路径"零 LLM 调用"由 A4-03 钉死
        if (outcome.isEmpty()) {
            if (outcome.vectorizationFailed()) {
                log.warn("问题向量化失败，本轮回退为空召回兜底: {}", question);
            }
            return chatSessionService.appendMessage(sessionId, "assistant",
                    "知识库中没有找到足够相关的内容，请换个问法或先上传相关文档。", null);
        }

        // 功能：用 promptTemplate 的 {context}/{question} 占位符组装最终 Prompt（context = 【参考N】片段 + 【历史问答记录】块）｜要点：Prompt 工程把结构化上下文喂给模型，约束其"基于参考作答"，降低幻觉
        String prompt = promptTemplate.replace("{context}", outcome.contextWithMemories())
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
        for (MilvusService.SearchResult sr : outcome.results()) {
            JSONObject source = new JSONObject();
            source.put("chunkId", sr.getChunkId());
            source.put("score", sr.getScore());
            String preview = sr.getContent() == null ? ""
                    : (sr.getContent().length() > 100
                            ? sr.getContent().substring(0, 100) + "..."
                            : sr.getContent());
            source.put("content", preview);
            sourcesArray.add(source);
        }
        String sources = sourcesArray.toJSONString();

        ChatMessage assistantMsg = chatSessionService.appendMessage(
                sessionId, "assistant", answer, sources);

        // 功能：把本次问答对存入长期记忆（旁路增强，失败只记日志不阻断）｜要点：质量门槛 MemoryService.SAVE_MIN_SCORE（0.6，全站唯一定义处）才存，确保记忆有可信来源支撑
        // 熔断降级产生的兜底文案虽然检索分可能够高，但内容不含任何知识——不得入库（degraded 拦截）
        if (!degraded && outcome.topScore() >= MemoryService.SAVE_MIN_SCORE) {
            memoryService.saveExchange(UserContext.getUserId(), question, answer);
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
        List<Map<String, Object>> history =
                chatSessionService.recentMessages(sessionId, AGENT_HISTORY_LIMIT);

        chatSessionService.appendMessage(sessionId, "user", question, null);

        AgentResult result = orchestratorAgent.executeResult(question, history);

        return chatSessionService.appendMessage(sessionId, "assistant",
                result.answer(), evidenceToSources(result.evidence()));
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
        return chatSessionService.getHistory(sessionId);
    }

    @Override
    public void deleteSession(Long sessionId) {
        chatSessionService.deleteSession(sessionId);
    }

    @Override
    public void updateTitle(Long sessionId, String title) {
        chatSessionService.updateTitle(sessionId, title);
    }
}
