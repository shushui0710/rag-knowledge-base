package com.liushuwen.rag.common;

/**
 * 用户上下文 - 基于 ThreadLocal 持有当前请求的用户ID
 *
 * 工作原理：
 *   JwtInterceptor 从 JWT 中解析出 userId 后，调用 UserContext.setUserId(userId)
 *   Service 层通过 UserContext.getUserId() 获取当前用户
 *   请求结束后 JwtInterceptor 调用 UserContext.clear() 清理
 *
 * 为什么用 ThreadLocal？
 *   Spring MVC 用线程池处理请求，每个请求在独立线程中执行。
 *   ThreadLocal 保证每个线程有自己的 userId 副本，线程之间互不干扰。
 *   这样就不需要把 userId 作为参数在 Controller → Service → Mapper 层层传递。
 *
 * 面试考点：
 *   Q: ThreadLocal 的原理？
 *   A: 每个 Thread 内部有一个 ThreadLocalMap，ThreadLocal.set() 实际是往当前线程的 Map 里存值。
 *      ThreadLocal.get() 从当前线程的 Map 里取值。线程结束后 Map 被回收。
 *
 *   Q: 为什么要 clear()？
 *   A: Tomcat 用线程池复用线程，如果不 clear，下一个请求会拿到上一个请求的 userId，造成数据泄漏。
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

    /**
     * 设置当前用户ID（由 JwtInterceptor 调用）
     */
    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    /**
     * 获取当前用户ID（由 Service 层调用）
     */
    public static Long getUserId() {
        Long userId = USER_ID_HOLDER.get();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        return userId;
    }

    /**
     * 清理当前线程的用户ID（由 JwtInterceptor 在 afterCompletion 中调用）
     */
    public static void clear() {
        USER_ID_HOLDER.remove();
    }
}
