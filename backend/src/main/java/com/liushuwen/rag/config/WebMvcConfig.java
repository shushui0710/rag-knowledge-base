package com.liushuwen.rag.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 - 注册 JWT 拦截器
 *
 * 拦截规则：
 *   /api/**          → 拦截（需要认证）
 *   /api/auth/**     → 排除（登录注册不需要token）
 *   /doc.html        → 排除（Knife4j 接口文档页面）
 *   /webjars/**      → 排除（Knife4j 静态资源）
 *   /v3/api-docs/**  → 排除（OpenAPI 规范文档）
 *
 * 面试考点：
 *   Q: WebMvcConfigurer 和 @Bean CorsFilter 的区别？
 *   A: WebMvcConfigurer 是 Spring MVC 配置回调，用于注册拦截器、视图解析器等。
 *      CorsFilter 是 Servlet Filter，在 DispatcherServlet 之前执行。
 *      两者不冲突，各管各的层。
 *
 *   Q: 拦截器(Interceptor)和过滤器(Filter)的区别？
 *   A: Filter 在 Servlet 容器层，Interceptor 在 Spring MVC 层。
 *      Interceptor 可以访问 Controller 方法信息（handler参数），Filter 不能。
 *      执行顺序：Filter → DispatcherServlet → Interceptor → Controller
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")           // 拦截所有 /api 开头的请求
                .excludePathPatterns(                  // 排除不需要认证的路径
                        "/api/auth/**",                // 登录、注册
                        "/doc.html",                   // Knife4j 文档页面
                        "/webjars/**",                 // Knife4j 静态资源
                        "/v3/api-docs/**",             // OpenAPI 文档
                        "/favicon.ico"
                );
    }
}
