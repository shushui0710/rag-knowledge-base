package com.liushuwen.rag.auth.controller;

import com.liushuwen.rag.auth.entity.User;
import com.liushuwen.rag.auth.service.UserService;
import com.liushuwen.rag.common.Result;
import com.liushuwen.rag.config.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户认证接口：提供注册、登录、获取当前用户信息，登录成功后由本层签发 JWT 并组装响应。
 * 【设计要点】分层职责边界：JWT 签发属 HTTP 传输层细节，放在 Controller 而非 Service，保证 Service 只处理业务逻辑
 * 【常见问题】token 为何不在 Service 生成？——Controller 负责协议相关（组装响应），Service 应与传输无关；返回前为何 setPassword(null)？——密码属敏感信息不回传前端
 */
@Tag(name = "用户认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<User> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        return Result.success(userService.register(username, password));
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        // 功能：调用 Service 验证密码并返回 User 对象｜要点：分层——业务逻辑与传输层解耦
        User user = userService.login(username, password);
        // 功能：Controller 层签发 JWT 并组装响应｜要点：无状态认证——token 由服务端签发、客户端持有
        String token = jwtUtil.generateToken(user.getId());
        // 功能：清空密码字段不回传｜要点：敏感信息最小化——即便加密也不应暴露给前端
        user.setPassword(null);
        return Result.success(Map.of("user", user, "token", token));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<User> me() {
        return Result.success(userService.getCurrentUser());
    }
}
