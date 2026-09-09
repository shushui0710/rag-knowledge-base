package com.liushuwen.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 配置：注册全局跨域过滤器，放行浏览器预检请求。
 * 【设计要点】CORS 预检：非简单请求浏览器先发 OPTIONS 预检，服务端须回 Allow-Methods/Origin，否则正式请求被拦截
 * 【常见问题】Filter 方案 vs 网关方案？——Filter 在应用内处理简单直接；微服务规模大时放网关统一管控更合理；allowCredentials 与 * 不能同用，故用 addAllowedOriginPattern
 */
@Configuration
public class CorsConfig {

    // 功能：构建 CorsFilter 放行所有来源/方法并缓存预检 3600s｜要点：addAllowedOriginPattern("*") 配合 credentials 兼容任意域名
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
