package com.liushuwen.rag.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：报告生成，RAG 检索主题片段后由 LLM 按"引言/现状/问题/建议"结构生成 Markdown 报告。
 * 【设计要点】生成式工具：RAG 检索 → 结构化 Prompt 约束输出 → LLM 生成，结果裁剪后返回
 * 【常见问题】工具为何失败返回文案而非抛异常？——错误回填 LLM 让其换方式，保 ReAct 循环不中断
 * 【缺陷修复·检索层用户隔离】修复前本工具调的是不带过滤的 search(vector, topK)，等价于"全库检索"——
 * 只要 REPORT 分支被接进产品链路，A 用户就可能在自己的报告里读到 B 用户的资料（跨用户向量泄露）。
 * 修复方式与 DocumentAgent / ChatServiceImpl 对齐：先按当前用户查出已向量化文档 ID，再让 Milvus
 * 用 expr 做服务端过滤（documentIds 为空时直接返回空，连一次注定为空的 RPC 都不发）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateReportTool implements Tool {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final LlmService llmService;
    /** 检索层用户隔离：查出当前用户已向量化文档 ID 列表，传给 Milvus expr 过滤 */
    private final DocumentMapper documentMapper;

    /** 报告依据片段数：报告需要覆盖多个侧面，比问答的 TopN 略宽 */
    private static final int REPORT_TOP_K = 5;

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

        // 功能：工具在 Agent 循环内执行｜要点：失败返回错误文案回填 LLM 使其换方式，抛异常会中断 ReAct 循环
        if (topic.isBlank()) {
            return "缺少报告主题参数 topic。";
        }
        try {
            // 1) 检索相关片段（带当前用户的文档隔离）
            List<float[]> vecs = embeddingService.embed(List.of(topic));
            if (vecs == null || vecs.isEmpty()) {
                return "文档向量化失败，请稍后重试。";
            }
            // 功能：检索层用户隔离——只在本用户已向量化的文档内检索｜要点：与 ChatServiceImpl/DocumentAgent 同一口径，
            // 否则报告会把其他用户的知识库内容写进来（documentIds 为空 ⇒ Milvus 直接返回空，不发无用 RPC）
            Long userId = UserContext.getUserId();
            List<Long> documentIds = null;
            if (userId != null) {
                documentIds = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                                .eq(Document::getUserId, userId)
                                .eq(Document::getEmbeddingStatus, 1)
                                .select(Document::getId))
                        .stream().map(Document::getId).toList();
            }
            List<MilvusService.SearchResult> hits = milvusService.search(vecs.get(0), REPORT_TOP_K, documentIds);
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
