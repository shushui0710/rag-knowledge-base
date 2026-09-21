package com.liushuwen.rag.agent;

import com.liushuwen.rag.llm.LlmService;
import com.liushuwen.rag.llm.Tool;
import com.liushuwen.rag.rag.RetrievalChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具：报告生成，RAG 检索主题片段后由 LLM 按"引言/现状/问题/建议"结构生成 Markdown 报告。
 * 【设计要点】生成式工具：检索 → 结构化 Prompt 约束输出 → LLM 生成，结果原样返回（{@link Tool#deliverable()} 为 true）
 * 【常见问题】工具为何失败返回文案而非抛异常？——错误回填 LLM 让其换方式，保 ReAct 循环不中断
 * 【缺陷修复·检索层用户隔离】修复前本工具调的是不带过滤的 search(vector, topK)，等价于"全库检索"——
 * 只要 REPORT 分支被接进产品链路，A 用户就可能在自己的报告里读到 B 用户的资料（跨用户向量泄露）。
 * 【缺陷修复·检索链第三份变体】修复后虽补上了用户隔离，但它是**纯稠密 TopK**——没有查询改写、没有 BM25 稀疏路、
 * 没有 Rerank、也没有 minScore 阈值，与主链路、DocumentAgent 各自为政（"三份实现、三种口径"）。
 * 报告本是"混合检索 + 重排"这套选型最该被看见的地方，却用了最弱的一路。现统一委托 {@code RetrievalChain}
 * （片段数由配置 rerankTopN 决定，与问答链一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateReportTool implements Tool {

    private final LlmService llmService;
    /** 全站唯一检索链：向量化 → 用户隔离 → 改写 → 混合检索 → 重排 → 阈值过滤 */
    private final RetrievalChain retrievalChain;

    @Override
    public String name() {
        return "generate_report";
    }

    @Override
    public String description() {
        return "基于知识库内容生成结构化报告（如整改报告、情况说明）。"
                + "当用户要求'生成一份关于XX的报告/总结'时使用。"
                + "参数：topic（报告主题）。注意：这是内容生成工具，需要先检索文档。";
    }

    @Override
    public String parametersJsonSchema() {
        return "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"topic\":{\"type\":\"string\",\"description\":\"报告主题\"}"
                + "},"
                + "\"required\":[\"topic\"]"
                + "}";
    }

    /**
     * 【产物型工具】本工具的返回值就是用户要的报告正文，必须原样交付，
     * 不允许被 LLM 概括成"报告已生成完成"（由 AgentExecutor 依据此标记优先采用产物原文作答）。
     */
    @Override
    public boolean deliverable() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String topic = arguments.get("topic") == null ? "" : String.valueOf(arguments.get("topic"));

        // 功能：工具在 Agent 循环内执行｜要点：失败返回错误文案回填 LLM 使其换方式，抛异常会中断 ReAct 循环
        if (topic.isBlank()) {
            return "缺少报告主题参数 topic。";
        }
        try {
            // 1) 检索相关片段：委托全站唯一检索链（用户隔离 / 查询改写 / 混合检索 / 重排 / 阈值过滤 全在链内）
            RetrievalChain.Outcome outcome = retrievalChain.retrieve(topic);
            if (outcome.vectorizationFailed()) {
                return "文档向量化失败，请稍后重试。";
            }
            if (outcome.isEmpty()) {
                return "未检索到与「" + topic + "」相关的文档，无法生成报告。";
            }
            // 2) 按报告结构生成（Markdown）。注意：报告只吃文档片段，不注入长期记忆——报告的依据必须来自知识库文档
            String prompt = "你是报告生成助手。请基于以下资料，生成一份结构化的"
                    + "「" + topic + "」报告（含：引言/现状/问题/建议）。\n\n资料：\n"
                    + outcome.contextBlock() + "\n要求：分点输出，Markdown 格式，引用资料中的具体内容。";
            String report = llmService.chat(prompt);
            // 功能：空返回显式兜底｜要点：不能让空串流回 ReAct —— LLM 只会转述"工具返回了空内容"，
            // 用户既拿不到报告、也不知道该修什么；给出可执行的排查方向比空串有用。
            if (report == null || report.isBlank()) {
                log.warn("报告生成为空: topic={}（若为思考模型，请检查 llm.deepseek.max-tokens 是否被 reasoning 预算耗尽）", topic);
                return "报告生成失败：模型返回空内容（输出预算可能被思考过程耗尽）。请稍后重试，或让运维检查 llm.deepseek.max-tokens 配置。";
            }
            return report;
        } catch (Exception e) {
            log.error("报告生成失败: topic={}, error={}", topic, e.getMessage(), e);
            return "报告生成失败：" + e.getMessage() + "，请稍后重试。";   // 返回文案，不抛异常
        }
    }
}
