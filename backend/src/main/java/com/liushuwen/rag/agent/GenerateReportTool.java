package com.liushuwen.rag.agent;

import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具3：报告生成（阶段4 ✅ 已实现）
 *
 * 演示"工具不只是查数据，也可以是生成类动作"。
 * 链路：RAG 检索主题相关片段 → 报告结构 Prompt → LLM 生成 Markdown 报告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateReportTool implements Tool {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final LlmService llmService;

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

    @Override
    public String execute(Map<String, Object> arguments) {
        String topic = arguments.get("topic") == null ? "" : String.valueOf(arguments.get("topic"));

        // ⚠️ 工具执行在 Agent 循环内：失败必须"返回错误文案"而不是抛异常
        //   （错误文案会回填给 LLM 让它换个方式，抛异常会炸掉整个 ReAct 循环）
        if (topic.isBlank()) {
            return "缺少报告主题参数 topic。";
        }
        try {
            // 1) 检索相关片段
            List<float[]> vecs = embeddingService.embed(List.of(topic));
            if (vecs == null || vecs.isEmpty()) {
                return "文档向量化失败，请稍后重试。";
            }
            List<MilvusService.SearchResult> hits = milvusService.search(vecs.get(0), 5);
            if (hits == null || hits.isEmpty()) {
                return "未检索到与「" + topic + "」相关的文档，无法生成报告。";
            }
            // 2) 组装上下文（空值防御）
            StringBuilder ctx = new StringBuilder();
            for (int i = 0; i < hits.size(); i++) {
                String content = hits.get(i).getContent();
                ctx.append("【参考").append(i + 1).append("】")
                        .append(content == null ? "" : content).append("\n\n");
            }
            // 3) 按报告结构生成（Markdown）
            String prompt = "你是报告生成助手。请基于以下资料，生成一份结构化的"
                    + "「" + topic + "」报告（含：引言/现状/问题/建议）。\n\n资料：\n"
                    + ctx + "\n要求：分点输出，Markdown 格式，引用资料中的具体内容。";
            return llmService.chat(prompt);
        } catch (Exception e) {
            log.error("报告生成失败: topic={}, error={}", topic, e.getMessage(), e);
            return "报告生成失败：" + e.getMessage() + "，请稍后重试。";   // 返回文案，不抛异常
        }
    }
}
