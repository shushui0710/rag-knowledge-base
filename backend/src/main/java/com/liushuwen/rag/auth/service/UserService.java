package com.liushuwen.rag.auth.service;

import com.liushuwen.rag.auth.entity.User;

/**
 * 用户服务接口：定义注册、登录、获取当前用户的业务契约。
 * 【设计要点】面向接口编程：Controller 依赖接口而非实现，便于替换实现与单元测试 Mock
 * 【常见问题】getCurrentUser 的 userId 从哪来？——由 JwtInterceptor 写入 UserContext 的 ThreadLocal；为什么用接口而非具体类？——解耦调用方与实现，利于 AOP 与多实现
 */
public interface UserService {

    User register(String username, String password);

    User login(String username, String password);

    User getCurrentUser();
}
