package com.liushuwen.rag.chat.controller;

import com.liushuwen.rag.common.Result;
import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;
import com.liushuwen.rag.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "智能问答")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "创建对话会话")
    @PostMapping("/session")
    public Result<ChatSession> createSession() {
        return Result.success(chatService.createSession());
    }

    @Operation(summary = "获取会话列表")
    @GetMapping("/sessions")
    public Result<List<ChatSession>> listSessions() {
        return Result.success(chatService.listSessions());
    }

    @Operation(summary = "发送问题并获取回答")
    @PostMapping("/ask/{sessionId}")
    public Result<ChatMessage> ask(@PathVariable Long sessionId, @RequestBody String question) {
        return Result.success(chatService.ask(sessionId, question));
    }

    @Operation(summary = "获取会话历史消息")
    @GetMapping("/history/{sessionId}")
    public Result<List<ChatMessage>> history(@PathVariable Long sessionId) {
        return Result.success(chatService.getHistory(sessionId));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(sessionId);
        return Result.success();
    }

    @Operation(summary = "更新会话标题")
    @PutMapping("/session/{sessionId}/title")
    public Result<Void> updateTitle(@PathVariable Long sessionId, @RequestBody String title) {
        chatService.updateTitle(sessionId, title);
        return Result.success();
    }
}
