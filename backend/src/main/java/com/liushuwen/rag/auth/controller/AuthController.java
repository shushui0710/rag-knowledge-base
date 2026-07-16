package com.liushuwen.rag.auth.controller;

import com.liushuwen.rag.auth.entity.User;
import com.liushuwen.rag.auth.service.UserService;
import com.liushuwen.rag.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "用户认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

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
        // TODO: 登录成功后返回JWT Token
        User user = userService.login(username, password);
        return Result.success(Map.of("user", user, "token", "TODO"));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<User> me() {
        return Result.success(userService.getCurrentUser());
    }
}
