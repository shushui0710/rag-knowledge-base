package com.liushuwen.rag.chat.service;

import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;

import java.util.List;
import java.util.Map;

/**
 * 会话生命周期服务：会话（chat_session）与消息（chat_message）的读写契约。
 * 【设计要点】从 ChatServiceImpl 拆出：问答编排（检索/生成）与"会话增删改查"是两个独立的变化方向——
 *   前者随 RAG/Agent 链路演进，后者只跟会话模型走，混在一个类里会让两种改动互相干扰。
 * 【职责边界】所有**按 id 的读写都必须做归属校验**：会话是用户私有资源，只校验"已登录"远远不够，
 *   任何人都能拿别人的 id 读到甚至删掉他人会话（典型 IDOR 越权）。
 */
public interface ChatSessionService {

    /** 创建会话，归属当前登录用户 */
    ChatSession createSession();

    /** 当前用户的会话列表（按更新时间倒序） */
    List<ChatSession> listSessions();

    /**
     * 读取会话历史（按创建时间升序）。
     * 会话不存在（含已逻辑删除）时返回空列表——保持"删除后读历史得到空"的既有契约；
     * 会话存在但不属于当前用户时抛业务码 403。
     */
    List<ChatMessage> getHistory(Long sessionId);

    /** 级联删除会话及其消息；非归属者抛业务码 403 */
    void deleteSession(Long sessionId);

    /** 更新会话标题；会话不存在或非归属者均抛业务异常 */
    void updateTitle(Long sessionId, String title);

    /** 追加一条消息（提问与回答共用入口），返回落库后的消息实体 */
    ChatMessage appendMessage(Long sessionId, String role, String content, String sources);

    /** 最近 limit 条有效消息（按时间正序，供多轮上下文使用） */
    List<Map<String, Object>> recentMessages(Long sessionId, int limit);
}
