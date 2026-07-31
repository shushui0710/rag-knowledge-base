package com.liushuwen.rag.auth.controller;

import com.liushuwen.rag.auth.entity.User;
import com.liushuwen.rag.auth.service.UserService;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.Result;
import com.liushuwen.rag.config.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户认证 Controller
 *
 * 职责分工：
 *   - register: 调 UserService 注册
 *   - login: 调 UserService 验证密码 → 验证通过后调 JwtUtil 生成 token → 返回 user + token
 *   - me: 调 UserService 从 UserContext 获取当前用户
 *
 * 为什么 token 在 Controller 生成而不是 Service？
 *   Service 层只管业务逻辑（验证密码），不应该关心 token 这种传输层细节。
 *   Controller 负责 HTTP 协议相关的事情（组装响应），生成 token 属于这一层。
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
        // Service 只管验证密码，返回 User 对象
        User user = userService.login(username, password);
        // Controller 负责生成 token 并组装响应
        String token = jwtUtil.generateToken(user.getId());
        // 密码不返回给前端
        user.setPassword(null);
        return Result.success(Map.of("user", user, "token", token));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<User> me() {
        return Result.success(userService.getCurrentUser());
    }
}
