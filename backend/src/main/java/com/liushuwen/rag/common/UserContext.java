package com.liushuwen.rag.common;

/**
 * 用户上下文：基于 ThreadLocal 持有当前请求的用户 ID，供跨层无侵入获取。
 * 【设计要点】ThreadLocal 内存模型：每个线程私有副本（ThreadLocalMap），避免 userId 逐层传参；但线程池复用须 remove 防脏数据
 * 【常见问题】为什么必须 clear()？——Tomcat 线程池复用线程，不清会被下一请求读到旧 userId 造成数据泄漏；getUserId 为 null 抛"未登录"意图？——强制拦截器先于业务执行
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

    /** 写入当前线程的 userId：由 JwtInterceptor 在 preHandle 解析 JWT 后调用。 */
    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    /** 读取当前线程的 userId：Service 层调用；未登录（为 null）抛"用户未登录"强制拦截器先行。 */
    public static Long getUserId() {
        Long userId = USER_ID_HOLDER.get();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        return userId;
    }

    /** 清理当前线程的 userId：由 JwtInterceptor 在 afterCompletion 调用，防线程池复用脏数据。 */
    public static void clear() {
        USER_ID_HOLDER.remove();
    }
}
