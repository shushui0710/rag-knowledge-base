package com.liushuwen.rag.chat.service;

import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;

import java.util.List;

/**
 * 对话服务契约（Controller 面向的唯一入口）。
 * 【职责边界】会话与消息的增删改查本身由 ChatSessionService（含归属校验）承担，
 *   本接口保留同名方法是为了让 Controller 只依赖一个门面，避免调用方在两层之间来回切换。
 */
public interface ChatService {

    ChatSession createSession();

    List<ChatSession> listSessions();

    ChatMessage ask(Long sessionId, String question);

    /**
     * 带链路模式的多轮问答：mode="agent" 走多 Agent 编排（意图路由 + 子 Agent + 反思评审），其余走默认 RAG 链路。
     * 【设计要点】两条链路共用同一入口与落库约束，前端只多传一个字段
     *
     * @param mode 链路模式，null/非法值 = 默认 RAG
     * @return 落库后的助手消息（含 sources）
     */
    ChatMessage ask(Long sessionId, String question, String mode);

    List<ChatMessage> getHistory(Long sessionId);

    void deleteSession(Long sessionId);

    void updateTitle(Long sessionId, String title);
}
