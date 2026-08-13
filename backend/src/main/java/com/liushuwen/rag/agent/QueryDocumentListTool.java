package com.liushuwen.rag.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具2：文档列表查询（阶段3 TODO）
 *
 * 骨架说明：当前为占位实现（execute 返回提示文本），
 * 填充后支持按分类过滤 + 数据隔离（UserContext）+ 分页（TODO 3-1b），
 * 可继续扩展：按标题模糊搜索、按向量化状态过滤、返回条数控制等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryDocumentListTool implements Tool {

    private final DocumentMapper documentMapper;

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

        // 阶段3 ✅ 已实现：文档列表查询（数据隔离 + 失败返回错误文案不抛异常）
        try {
            LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Document::getUserId, UserContext.getUserId());      // 数据隔离，必加！
            if (category != null && !category.isBlank()) {
                wrapper.eq(Document::getCategory, category);
            }
            wrapper.orderByDesc(Document::getCreateTime).last("limit 10"); // 最多10条
            List<Document> docs = documentMapper.selectList(wrapper);
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
            // ⚠️ 工具内 DB 查询失败：返回错误文案（Agent 会回填给 LLM），不抛异常
            log.error("查询文档列表失败: category={}, error={}", category, e.getMessage(), e);
            return "查询文档列表失败：" + e.getMessage() + "，请稍后重试。";
        }
    }
}
