package com.liushuwen.rag.agent;
import com.liushuwen.rag.llm.Tool;
import com.liushuwen.rag.metrics.AgentMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册表：Spring 启动时收集所有 Tool Bean，按 name 建 Map 索引，并作为**工具执行的唯一出口**（execute）。
 * 【设计要点】注册表模式 + 依赖收集：构造器注入 List<Tool>，Spring 自动注入全部实现（开闭原则）
 * 【设计要点】执行收口：工具执行计数（toolCalls）只写在 execute 内。修复前计数散落在调用点——
 * AgentExecutor 的 ReAct 循环里记一次，而 StatsAgent 直接调 tool.execute() 完全绕过埋点，
 * 导致编排链路的工具调用从不计数（漏记）；执行收口后所有调用方走同一方法，漏记/重记都无从发生。
 * 【常见问题】新增一个工具要改注册表吗？——不用，新增 @Component 实现类即自动注册，零侵入扩展
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<Tool> tools;
    /** 工具执行计数埋点（唯一写者：本类的 execute 方法） */
    private final AgentMetrics metrics;

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

    /**
     * 执行工具的**唯一入口**：查表 → 执行 → 计数。
     * 【设计要点】把"找工具 + 跑工具 + 记指标"合并成一个出口，调用方无需（也不该）自己记指标；
     * 异常直接向上抛，由调用方决定兜底文案（ReAct 循环回填错误让 LLM 自纠、StatsAgent 返回失败文案）
     *
     * @param name 工具名
     * @param args 已解析的参数（JSON 字符串解析后的 Map）
     * @return 工具输出；工具不存在时返回统一错误文案（而非抛异常，便于 LLM 读到后自我调整）
     */
    public String execute(String name, Map<String, Object> args) {
        Tool tool = get(name);
        if (tool == null) {
            return "错误：工具不存在 " + name;
        }
        String result = tool.execute(args);
        metrics.recordToolCall(name);
        return result;
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
