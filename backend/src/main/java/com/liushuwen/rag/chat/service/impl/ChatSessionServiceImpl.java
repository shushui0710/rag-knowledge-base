package com.liushuwen.rag.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;
import com.liushuwen.rag.chat.mapper.ChatMessageMapper;
import com.liushuwen.rag.chat.mapper.ChatSessionMapper;
import com.liushuwen.rag.chat.service.ChatSessionService;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话生命周期实现：会话/消息的增删改查 + 归属校验，是对话数据的唯一读写出口。
 * 【设计要点】归属校验下沉到服务层：Controller 只负责鉴权（有没有 token），
 *   "这个资源是不是你的"必须在服务层判断，否则每个入口都要重复一遍且极易漏。
 * 【常见问题】为什么读历史时"会话不存在"返回空列表、"不属于自己"才报错？
 *   —— 前者是既有契约（A4-06 删除会话后读历史应为空），后者是安全边界，两者语义不同不可合并。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    /**
     * 会话标题长度上限：与 chat_session.title 的 VARCHAR(100) 对齐。
     * 【缺陷修复·超长输入落 500（A7 补射程发现）】修复前超长标题不命中任何业务校验，
     * 会一路走到 UPDATE 才被数据库按列上限拒绝，抛出的不是 BusinessException 而是落进
     * GlobalExceptionHandler 的兜底分支 ⇒ 用户看到 HTTP 500「系统内部错误」，
     * 而真实原因是"标题太长"。校验前置后同类问题统一回到 HTTP 400 + 可读文案。
     */
    private static final int MAX_TITLE_LENGTH = 100;

    @Override
    public ChatSession createSession() {
        ChatSession session = new ChatSession();
        session.setTitle("新对话");
        // 功能：创建时把当前登录用户 ID 写入会话，实现归属与数据隔离
        // 要点：JWT 解析出的 userId 经 ThreadLocal 注入 UserContext，避免越权访问他人会话
        session.setUserId(UserContext.getUserId());
        chatSessionMapper.insert(session);
        return session;
    }

    @Override
    public List<ChatSession> listSessions() {
        // 功能：按当前用户 ID 过滤会话列表并按更新时间倒序｜要点：多租户隔离（WHERE user_id = ?）
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId, UserContext.getUserId())
                .orderByDesc(ChatSession::getUpdateTime);
        return chatSessionMapper.selectList(wrapper);
    }

    @Override
    public List<ChatMessage> getHistory(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            // 会话不存在/已删除：历史视为空（保持既有契约——删除后读历史不应报错）
            return List.of();
        }
        requireOwner(session);
        // 功能：按 sessionId 过滤历史消息并按创建时间升序｜要点：按会话隔离 + 时间排序还原对话顺序
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreateTime));
    }

    @Override
    public void deleteSession(Long sessionId) {
        requireOwner(requireSession(sessionId));
        // 功能：级联删除——先删子表（消息）再删父表（会话）｜要点：级联顺序避免孤儿记录；
        // 两张表都有 deleted 列，逻辑删除由 MyBatis-Plus 自动改写成 UPDATE 实现软删
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId));
        chatSessionMapper.deleteById(sessionId);
        log.info("删除会话: {}", sessionId);
    }

    @Override
    public void updateTitle(Long sessionId, String title) {
        // 功能：长度校验前置（理由见 MAX_TITLE_LENGTH 注释）｜要点：校验按"客户端输入错误"处理，归 400 而非 500
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException("会话标题过长，不得超过 " + MAX_TITLE_LENGTH
                    + " 个字符，当前 " + title.length() + " 个");
        }
        ChatSession session = requireOwner(requireSession(sessionId));
        session.setTitle(title);
        chatSessionMapper.updateById(session);
        log.info("更新会话标题: {} -> {}", sessionId, title);
    }

    @Override
    public ChatMessage appendMessage(Long sessionId, String role, String content, String sources) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSources(sources);
        chatMessageMapper.insert(msg);
        return msg;
    }

    @Override
    public List<Map<String, Object>> recentMessages(Long sessionId, int limit) {
        // 功能：只取最近 limit 条有效消息｜要点：多轮上下文要控 token，越早的轮次对当前提问价值越低
        List<ChatMessage> all = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreateTime));
        List<Map<String, Object>> history = new ArrayList<>();
        int from = Math.max(0, all.size() - Math.max(0, limit));
        for (int i = from; i < all.size(); i++) {
            ChatMessage m = all.get(i);
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;                                  // 空值防御：空内容不进上下文
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "message");
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            history.add(item);
        }
        return history;
    }

    /** 取会话，不存在则抛业务异常 */
    private ChatSession requireSession(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在: " + sessionId);
        }
        return session;
    }

    /**
     * 归属校验：会话必须属于当前登录用户。
     * 【缺陷修复·会话越权（原 A6-01/A6-02 缺口①）】修复前只按 id 读写、不校验归属：
     * 任何登录用户拿着别人的 sessionId 就能读到甚至删掉他人会话，而当时 66 条验收用例全绿——
     * 因为 A4-05 的标题声称"B 也读不到 A 的历史"，正文却只记录条数不断言（声明的射程 > 实际射程）。
     * 修法：把归属校验收口到本方法，所有按 id 的入口（读历史/删除/改标题）都必须先过它。
     */
    private ChatSession requireOwner(ChatSession session) {
        if (!Objects.equals(session.getUserId(), UserContext.getUserId())) {
            throw new BusinessException(403, "无权操作他人会话: " + session.getId());
        }
        return session;
    }
}
