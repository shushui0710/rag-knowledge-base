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
 * 报告生成 Agent（阶段4 ✅ 已实现）
 *
 * 链路：RAG 检索主题片段 → "报告结构" Prompt（引言/现状/问题/建议）→ LLM 生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAgent implements Agent {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final LlmService llmService;

    @Override
    public AgentType type() {
        return AgentType.REPORT;
    }

    @Override
    public String execute(String task, List<Map<String, Object>> history) {
        try {
            // 1) RAG 检索 task 相关文档片段
            List<float[]> vecs = embeddingService.embed(List.of(task));
            if (vecs == null || vecs.isEmpty()) {
                return "文档向量化失败，请稍后重试。";
            }
            List<MilvusService.SearchResult> hits = milvusService.search(vecs.get(0), 5);
            if (hits == null || hits.isEmpty()) {
                return "未检索到与「" + task + "」相关的文档，无法生成报告。";
            }
            // 2) 组装上下文（空值防御）
            StringBuilder ctx = new StringBuilder();
            for (int i = 0; i < hits.size(); i++) {
                String content = hits.get(i).getContent();
                ctx.append("【参考").append(i + 1).append("】")
                        .append(content == null ? "" : content).append("\n\n");
            }
            // 3) 报告结构 Prompt 生成（Markdown）
            String prompt = "你是报告生成助手。请基于以下资料，生成一份结构化的"
                    + "「" + task + "」报告（含：引言/现状/问题/建议）。\n\n资料：\n"
                    + ctx + "\n要求：分点输出，Markdown 格式，引用资料中的具体内容。";
            return llmService.chat(prompt);
        } catch (Exception e) {
            log.error("报告生成失败: task={}, error={}", task, e.getMessage(), e);
            return "报告生成失败：" + e.getMessage() + "，请稍后重试。";
        }
    }
}
