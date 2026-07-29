package com.liushuwen.rag.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate配置类 - 统一创建RestTemplate实例，由Spring容器管理
 *
 * 为什么要用@Bean而不是 new RestTemplate()？
 *
 * 1. 超时控制：裸 new RestTemplate() 没有超时设置，调第三方API时如果对方不响应，
 *    线程会一直阻塞直到Tomcat默认超时（可能几分钟），生产环境这很危险。
 *    通过@Bean统一设置连接超时10s + 读取超时60s。
 *
 * 2. 可测试性：@Bean创建的对象在单元测试时可以用@MockBean替换，
 *    而 new 出来的对象无法被Spring替换，测试时只能用Mockito.mockConstruction（很麻烦）。
 *
 * 3. 统一管理：如果以后需要加拦截器（如请求日志、链路追踪）、统一加Header，
 *    只需要改这一个地方，所有Service自动生效。
 *
 * 面试考点：Spring Bean的生命周期管理 vs 直接new的区别
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 创建RestTemplate并设置超时时间
     *
     * RestTemplateBuilder是Spring Boot提供的构建器，
     * 比直接 new RestTemplate() 多了自动配置消息转换器、拦截器等能力。
     *
     * @param builder Spring Boot自动注入的RestTemplateBuilder
     * @return 配置好超时的RestTemplate实例
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))   // 连接超时：10秒（连不上API服务器就放弃）
                .setReadTimeout(Duration.ofSeconds(60))      // 读取超时：60秒（AI API生成回答可能较慢）
                .build();
    }
}
