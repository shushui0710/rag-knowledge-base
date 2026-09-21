package com.liushuwen.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务基建：开启 @Async 并为"运维长任务"提供专用线程池。
 * 【设计要点】为什么单独建池而不复用默认执行器：索引重建是分钟级长任务，若落在
 *   Spring Boot 默认的 applicationTaskExecutor 上，长任务会占满共享池、拖慢其他 @Async 使用方；
 *   独立命名池（ops-*）让"运维负载"与"业务异步"物理隔离，也便于按线程名定位日志。
 * 【常见问题】@Async 为什么常"不生效"？——同类内部自调用不经过代理，注解无效；
 *   必须由别的 Bean 调进来（本项目由 IndexRebuildServiceImpl 调用 IndexRebuildWorker）。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** 运维任务线程池 Bean 名，供 @Async("opsTaskExecutor") 引用 */
    public static final String OPS_EXECUTOR = "opsTaskExecutor";

    /**
     * 运维任务专用线程池。
     * 【设计要点】核心 1 线程：同一时刻只允许一个运维任务在跑（并发入口由 IndexRebuildWorker 再兜一层），
     *   队列 4 只作为缓冲；AbortPolicy 让"池满"显式抛错而不是静默丢任务；
     *   waitForTasksToCompleteOnShutdown=true 保证关闭时不腰斩正在提交向量的重建任务。
     * 【常见问题】为什么不让它无限排队？——破坏性运维任务的正确姿势是"拒绝并让调用方稍后重试"，
     *   静默堆积只会让运维人员误以为没生效而重复触发。
     */
    @Bean(OPS_EXECUTOR)
    public ThreadPoolTaskExecutor opsTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("ops-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return opsTaskExecutor();
    }

    /**
     * @Async 的"逃逸异常"兜底。
     * 【设计要点】void 异步方法抛出的异常不会传播给调用方，若不实现本处理器会被静默吞掉；
     *   本项目 IndexRebuildWorker 内部已 catch 并落任务状态，这里只作为最后一道日志兜底。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("异步任务未捕获异常: {}", method.getName(), ex);
    }
}
