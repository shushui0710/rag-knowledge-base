package com.liushuwen.rag.chat.service;

import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;

import java.util.List;

public interface ChatService {

    ChatSession createSession();

    List<ChatSession> listSessions();

    ChatMessage ask(Long sessionId, String question);

    List<ChatMessage> getHistory(Long sessionId);

    void deleteSession(Long sessionId);

    void updateTitle(Long sessionId, String title);
}
