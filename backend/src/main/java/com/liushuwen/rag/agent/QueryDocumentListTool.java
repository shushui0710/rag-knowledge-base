package com.liushuwen.rag.agent;

import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.service.DocumentService;
import com.liushuwen.rag.llm.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：文档列表查询，按分类（可选）过滤、仅查当前用户文档、最多返回 10 条，失败返回错误文案。
 * 【设计要点】数据隔离 + 上限防护：隔离与 limit 由 DocumentService 承担，工具只负责"取数 + 排版给 LLM"
 * 【职责边界】工具**不直连 Mapper**：持久化与隔离规则属 document 模块，工具越界会让同一条口径在多处实现
 * 【常见问题】工具内 DB 失败为何返回文案不抛异常？——错误回填 LLM 让其重试，而非炸掉 ReAct 循环
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryDocumentListTool implements Tool {

    private final DocumentService documentService;

    /** 单次返回上限：控制注入 LLM 的上下文长度，避免长列表挤占答案预算 */
    private static final int MAX_ROWS = 10;

    @Override
    public String name() {
        return "query_document_list";
    }

    @Override
    public String description() {
        return "查询知识库的文档列表，可按分类过滤。"
                + "当用户询问'有哪些文档''列出XX分类的文档'时使用。"
                + "参数：category（可选，分类名，如'规章制度'）。"
                + "注意：要了解数量统计请用 query_document_stats；问具体内容请用 RAG 检索。";
    }

    @Override
    public String parametersJsonSchema() {
        return "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"category\":{\"type\":\"string\",\"description\":\"文档分类，可选\"}"
                + "},"
                + "\"required\":[]"
                + "}";
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String category = arguments.get("category") == null ? null : String.valueOf(arguments.get("category"));

        // 功能：文档列表查询（数据隔离 + 失败返回错误文案不抛异常）｜要点：MAX_ROWS 控返回量
        try {
            List<Document> docs = documentService.listByCategory(category, MAX_ROWS);
            if (docs == null || docs.isEmpty()) {
                return "知识库中暂无文档" + (category == null ? "" : "（分类：" + category + "）");
            }
            StringBuilder sb = new StringBuilder("文档列表：\n");
            for (int i = 0; i < docs.size(); i++) {
                Document d = docs.get(i);
                sb.append(i + 1).append(". 《").append(d.getTitle()).append("》")
                        .append("（").append(d.getCategory()).append("，")
                        .append(d.getEmbeddingStatus() != null && d.getEmbeddingStatus() == 1
                                ? "已向量化" : "待处理").append("）\n");     // 空值防御
            }
            return sb.toString().trim();
        } catch (Exception e) {
            // 功能：工具内 DB 失败返回错误文案（回填 LLM 让其重试）｜要点：不抛异常避免炸掉 ReAct 循环
            log.error("查询文档列表失败: category={}, error={}", category, e.getMessage(), e);
            return "查询文档列表失败：" + e.getMessage() + "，请稍后重试。";
        }
    }
}
