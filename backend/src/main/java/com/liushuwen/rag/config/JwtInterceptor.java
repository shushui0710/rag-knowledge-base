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
 * JWT 登录拦截器：校验 token 并把 userId 写入 ThreadLocal（UserContext）。
 * 【设计要点】拦截器 vs 过滤器：执行时机（DispatcherServlet 前后）与依赖（Servlet 规范 vs Spring 上下文），拦截器能拿到 handler 方法信息
 * 【常见问题】userId 为何不放方法参数逐层传？——ThreadLocal 对业务代码无侵入，但要防线程池复用脏数据；清理为何放 afterCompletion？——无论成功失败都执行，异常时 postHandle 不跑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 功能：从 Authorization 头提取 Bearer token｜要点：认证信息传递的标准位置
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException("未提供认证令牌，请先登录");
        }

        // 功能：截取 "Bearer " 前缀后的 token 串｜要点：协议约定的令牌前缀
        String token = authHeader.substring(7);

        // 功能：校验 token 签名与过期｜要点：无状态认证——服务端不查库即可鉴权
        if (!jwtUtil.validateToken(token)) {
            throw new BusinessException("认证令牌无效或已过期，请重新登录");
        }

        // 功能：解析 userId 并写入 UserContext(ThreadLocal)｜要点：跨层传参不污染方法签名
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
        // 功能：无论成功或异常都清理 ThreadLocal｜要点：线程池复用须 remove 防脏数据
        UserContext.clear();
    }
}
