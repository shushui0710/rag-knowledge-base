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
 * 工具注册表：Spring 启动时收集所有 Tool Bean，按 name 建 Map 索引，供 AgentExecutor 按名映射执行器。
 * 【设计要点】注册表模式 + 依赖收集：构造器注入 List<Tool>，Spring 自动注入全部实现（开闭原则）
 * 【常见问题】新增一个工具要改注册表吗？——不用，新增 @Component 实现类即自动注册，零侵入扩展
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
