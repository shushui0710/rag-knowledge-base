package com.liushuwen.rag.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册表（阶段3）
 *
 * Spring 启动时自动收集所有 Tool 类型的 Bean，按 name 索引。
 * AgentExecutor 通过它把 LLM 返回的工具名映射到实际执行器。
 *
 * 面试考点：
 * - 依赖收集：构造器注入 List<Tool>，Spring 会把所有 Tool 实现 Bean 注入进来
 *   ——这就是"策略模式"的 Spring 化实现
 * - 新工具上线只需新增一个 @Component 实现类，注册表自动感知（开闭原则）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<Tool> tools;

    /** name -> Tool 的索引 */
    private Map<String, Tool> index;

    /** 懒加载索引（首次使用构建） */
    private Map<String, Tool> getIndex() {
        if (index == null) {
            synchronized (this) {
                if (index == null) {
                    index = tools.stream()
                            .collect(Collectors.toConcurrentMap(Tool::name, t -> t, (a, b) -> a));
                    log.info("工具注册完成: {}", index.keySet());
                }
            }
        }
        return index;
    }

    /** 按名称取工具；不存在返回 null（调用方需兜底） */
    public Tool get(String name) {
        return getIndex().get(name);
    }

    /** 全部工具（用于构建 LLM 的 tools 参数） */
    public List<Tool> all() {
        return Collections.unmodifiableList(tools);
    }

    /** 已注册的工具名（调试用） */
    public String names() {
        return String.join(", ", getIndex().keySet());
    }
}
