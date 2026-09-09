package com.liushuwen.rag.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate 配置：以 @Bean 统一管理 RestTemplate 并设置超时。
 * 【设计要点】@Bean 托管 vs 直接 new：由容器管理可统一超时、便于 @MockBean 测试、可集中加拦截器
 * 【常见问题】为什么必须设超时？——裸 new 无超时，第三方不响应会一直阻塞线程直至容器超时；连接/读取超时为啥分开？——建连慢与响应慢是两类故障，分别控制
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 创建 RestTemplate 并设置超时。
     * 【设计要点】RestTemplateBuilder：Spring Boot 提供的构建器，已预置消息转换器/拦截器，优于裸 new RestTemplate()
     * 【常见问题】为何注入 builder 而非 new？——Builder 承接 Boot 自动配置，超时等定制在其上追加，避免遗漏默认能力
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))   // 功能：连接超时 10s｜要点：连不上立即放弃，释放线程
                .setReadTimeout(Duration.ofSeconds(60))      // 功能：读取超时 60s｜要点：AI 生成慢，给足时间又封顶
                .build();
    }
}
