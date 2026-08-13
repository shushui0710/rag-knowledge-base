package com.liushuwen.rag.rag;

/**
 * 意图路由服务（阶段3）
 *
 * 判断用户问题需要哪种能力，决定走哪条链路。
 * 实现见 RouterServiceImpl（当前为骨架：默认返回 DOCUMENT）。
 */
public interface RouterService {

    /**
     * 路由判断
     *
     * @param question 用户问题
     * @return DOCUMENT / STATS / HYBRID
     */
    Route route(String question);
}
