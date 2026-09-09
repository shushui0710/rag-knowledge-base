package com.liushuwen.rag.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 JWT 拦截器并声明拦截/排除路径。
 * 【设计要点】拦截器 vs 过滤器：Interceptor 在 Spring MVC 层（能拿到 handler 方法信息），Filter 在 Servlet 容器层；执行顺序 Filter→DispatcherServlet→Interceptor→Controller
 * 【常见问题】排除路径为何要精确？——错误排除会漏过鉴权（如把 /api/auth/me 也放行），须逐个列清免认证路径
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")           // 功能：拦截所有 /api 请求｜要点：统一鉴权入口
                .excludePathPatterns(                  // 功能：列出免认证路径｜要点：错误排除会漏过鉴权
                        // 常见问题：为何精确排除而非 "/api/auth/**"？→ 否则连 /api/auth/me 一并放行，导致拿不到 userId 恒报未登录
                        "/api/auth/login",             // 登录（免认证）
                        "/api/auth/register",          // 注册（免认证）
                        "/doc.html",                   // Knife4j 文档页面
                        "/webjars/**",                 // Knife4j 静态资源
                        "/v3/api-docs/**",             // OpenAPI 文档
                        "/favicon.ico"
                );
    }
}
