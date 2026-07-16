package com.liushuwen.rag.chat.service.impl;

import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;
import com.liushuwen.rag.chat.mapper.ChatMessageMapper;
import com.liushuwen.rag.chat.mapper.ChatSessionMapper;
import com.liushuwen.rag.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Override
    public ChatSession createSession() {
        ChatSession session = new ChatSession();
        session.setTitle("新对话");
        chatSessionMapper.insert(session);
        return session;
    }

    @Override
    public List<ChatSession> listSessions() {
        return chatSessionMapper.selectList(null);
    }

    @Override
    public ChatMessage ask(Long sessionId, String question) {
        // TODO: 实现RAG问答核心流程
        // 1. 将question向量化
        // 2. Milvus检索Top-K相关文档块
        // 3. 拼接Prompt（上下文 + 问题）
        // 4. 调用大模型API生成回答
        // 5. 存储问答消息到MySQL
        // 6. 返回回答 + 来源引用
        // 注意：此步骤需要跨模块调用 DocumentService 获取检索上下文
        log.info("问答请求 - 会话:{}, 问题:{}", sessionId, question);
        throw new BusinessException("智能问答功能待实现 - 第4周开发");
    }

    @Override
    public List<ChatMessage> getHistory(Long sessionId) {
        // TODO: 查询会话历史消息
        return chatMessageMapper.selectList(null);
    }

    @Override
    public void deleteSession(Long sessionId) {
        chatSessionMapper.deleteById(sessionId);
        chatMessageMapper.delete(null);
        log.info("删除会话: {}", sessionId);
    }
}
