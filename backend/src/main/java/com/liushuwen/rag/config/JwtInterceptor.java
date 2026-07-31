package com.liushuwen.rag.config;

import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器 - 拦截所有需要认证的请求
 *
 * 工作流程：
 *   1. preHandle: 请求到达 Controller 之前执行
 *      - 从 Header 提取 Authorization: Bearer <token>
 *      - 解析 token 得到 userId
 *      - 存入 UserContext (ThreadLocal)
 *      - 返回 true 放行，返回 false 拦截
 *   2. afterCompletion: Controller 处理完之后执行
 *      - 清理 UserContext，防止线程池复用导致数据泄漏
 *
 * 面试考点：
 *   Q: HandlerInterceptor 的三个方法？
 *   A: preHandle（Controller前）、postHandle（Controller后视图前）、afterCompletion（完全结束后）
 *
 *   Q: 为什么在 afterCompletion 而不是 postHandle 清理 ThreadLocal？
 *   A: postHandle 在 Controller 抛异常时不会执行，afterCompletion 无论成功失败都执行。
 *      如果在 postHandle 清理，异常情况下 ThreadLocal 不会被清理，导致线程池中下一个请求拿到错误的 userId。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 从 Header 提取 token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException("未提供认证令牌，请先登录");
        }

        // 2. 提取 Bearer 后面的 token
        String token = authHeader.substring(7);

        // 3. 验证 token
        if (!jwtUtil.validateToken(token)) {
            throw new BusinessException("认证令牌无效或已过期，请重新登录");
        }

        // 4. 解析 userId，存入 UserContext
        Long userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new BusinessException("认证令牌解析失败，请重新登录");
        }

        UserContext.setUserId(userId);
        log.debug("认证通过 - userId: {}", userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        // 无论请求成功还是异常，都清理 ThreadLocal
        UserContext.clear();
    }
}
