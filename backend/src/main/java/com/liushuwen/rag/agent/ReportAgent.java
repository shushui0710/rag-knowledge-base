package com.liushuwen.rag.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 报告生成 Agent：承接编排路由的 REPORT 分支，把"写一份关于 XX 的报告"这类任务交给 ReAct 循环完成。
 * 【设计要点】本类刻意做"薄"——它不自己检索、不自己拼 Prompt，只把任务转交给 AgentExecutor：
 * 报告生成的关键在于"LLM 自主决定调用 generate_report 工具"，而这正是 ReAct 循环的职责，
 * 在这里重写一遍检索+生成逻辑只会得到第二套实现（多一处口径、多一处漏埋点）。
 * 【设计要点·为什么它让链路③有了产品入口】此前的架构里 ReAct 循环（含 generate_report 工具）
 * 只有裸接口 /api/agent/ask 够得着，对话页永远走不到 ⇒ 报告能力对用户等于不存在。
 * 把 REPORT 接进路由后，用户在对话页开「深度思考」说"生成一份关于 XX 的报告"即可命中，
 * ReAct 能力随之从"孤岛"变成编排链路的一条正常分支（回答照常落库、依据照常可追溯）。
 * 【常见问题】REPORT 为什么不做 Critic 反思重写？——反思重写的 Prompt 是"更直接地回答用户问题"，
 * 面向短问答设计；报告是长结构化产物，重写会破坏其章节结构。详见 OrchestratorAgent 的分支说明。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAgent implements Agent {

    /** ReAct 循环执行器：报告生成复用它的"思考→调工具→观察"循环与 generate_report 工具 */
    private final AgentExecutor agentExecutor;

    @Override
    public AgentType type() {
        return AgentType.REPORT;
    }

    @Override
    public AgentResult execute(String task, List<Map<String, Object>> history) {
        log.info("[ReportAgent] 转交 ReAct 循环生成报告: {}", task);
        // 多轮历史透传给 ReAct 循环：报告类任务常有多轮追加（"把第二节展开"），丢历史会让追问断上下文
        return agentExecutor.executeResult(task, history);
    }
}
