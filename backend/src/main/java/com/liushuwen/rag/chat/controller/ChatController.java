package com.liushuwen.rag.chat.controller;

import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.Result;
import com.liushuwen.rag.chat.entity.ChatMessage;
import com.liushuwen.rag.chat.entity.ChatSession;
import com.liushuwen.rag.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 智能问答 REST 控制器，承接前端问答与会话管理请求。
 * 在链路中处于入口层：DTO 接收与字段校验、JWT 用户隔离（userId 取自 ThreadLocal），再委托 ChatService。
 * 【设计要点】@RequestBody DTO 绑定与字段校验、会话增删改查的 REST 语义、基于 ThreadLocal 的用户隔离防越权
 * 【常见问题】为什么提问用 DTO 而非裸 String？——String 只能接原文无法按字段校验，DTO 走 Jackson 反序列化可做空值/格式校验；userId 从哪来？——JWT 拦截器解析后存入 UserContext（ThreadLocal），全链路可拿
 */
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

    /**
     * 问答入口：DTO 接收提问 → 空值校验 → 委托 ChatService.ask 按 mode 选择链路执行。
     * 【设计要点】@RequestBody DTO 绑定与字段校验、入口层只做参数合法性，业务逻辑沉降到 Service
     * 【常见问题】为什么用 DTO 而非 @RequestBody String？——DTO 经 Jackson 反序列化可逐字段校验，裸 String 只能拿到原文；会话归属由哪层校验？——Service 内做归属校验，保证任何入口调用都安全
     * 【常见问题】mode 是什么？——链路开关：不传 = 默认 RAG 链路；"agent" = 多 Agent 编排（对话页「深度思考」）。
     * 之所以复用这个端点而不是让前端直接调 /api/agent/orchestrate：裸端点不落库，回答进不了会话历史、刷新即丢。
     */
    @Operation(summary = "发送问题并获取回答")
    @PostMapping("/ask/{sessionId}")
    public Result<ChatMessage> ask(@PathVariable Long sessionId, @RequestBody AskRequest req) {
        // 功能：空问题抛业务异常，由全局异常处理器统一转 HTTP 400｜要点：@RequestBody 绑定原理（String 只能接原文，DTO 走 Jackson 反序列化）
        // 【缺陷修复·HTTP 语义双轨】修复前此处写的是 return Result.error(400, "问题不能为空")：
        // 方法正常返回 → Spring 按 HTTP 200 序列化，业务码只落在响应体的 code 字段；
        // 而 Service 抛 BusinessException 时 GlobalExceptionHandler 的 @ResponseStatus(BAD_REQUEST)
        // 会返回真正的 HTTP 400。同一类客户端错误出现两种 HTTP 表现，会误导网关、监控、第三方集成等
        // 按 HTTP 语义判断的调用方。改为抛异常，使「参数校验」与「业务校验」收敛到同一出口。
        if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        return Result.success(chatService.ask(sessionId, req.getQuestion(), req.getMode()));
    }

    /** 提问请求体 DTO：以对象收 JSON，借助 Jackson 反序列化按字段校验（裸 String 无法做字段级校验） */
    @lombok.Data
    public static class AskRequest {
        private String question;
        /** 链路模式：null/未传 = 默认 RAG 链路；"agent" = 多 Agent 编排（前端「深度思考」开关） */
        private String mode;
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
