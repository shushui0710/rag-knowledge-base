package com.liushuwen.rag.agent;

import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.document.dto.DocumentStats;
import com.liushuwen.rag.document.service.DocumentService;
import com.liushuwen.rag.llm.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具：文档统计查询，返回当前用户文档总数、向量化/未向量化数量、分类统计。
 * 【设计要点】统计口径全部下沉到 DocumentService#stats（含按 document_id 去重），工具只做取数与排版
 * 【职责边界】工具**不直连 Mapper**：统计口径属 document 模块，散落到工具里就会出现"同一系统两套口径"
 * 【常见问题】为什么工具结果要裁剪/脱敏？——只回传 LLM 决策所需信息，控制 token 与避免泄露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryDocumentStatsTool implements Tool {

    private final DocumentService documentService;

    @Override
    public String name() {
        return "query_document_stats";
    }

    @Override
    public String description() {
        return "查询知识库文档的统计信息：文档总数、已向量化数量、未向量化数量、按分类统计。"
                + "当用户询问'有多少文档''哪些文档没处理''文档统计'等涉及数量/状态的问题时使用。"
                + "注意：这是数据统计工具，不是文档内容检索工具，问具体内容不要用我。";
    }

    @Override
    public String parametersJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        // 功能：取当前用户的文档统计（隔离与去重口径均由 DocumentService#stats 保证）
        DocumentStats stats = documentService.stats();

        log.info("[Tool:{}] 统计结果(userId={}): total={}, embedded={}, withChunk={}",
                name(), UserContext.getUserId(),
                stats.total(), stats.embedded(), stats.withChunk());

        return "知识库共 " + stats.total() + " 篇文档，其中 " + stats.embedded() + " 篇已完成向量化，"
                + (stats.total() - stats.embedded()) + " 篇待处理；有内容分块的文档 "
                + stats.withChunk() + " 篇。";
    }
}
