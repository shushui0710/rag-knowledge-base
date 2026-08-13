package com.liushuwen.rag.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.entity.DocumentChunk;
import com.liushuwen.rag.document.mapper.DocumentChunkMapper;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具1：文档统计查询（阶段3第一个真实工具）
 *
 * ✅ 本文件即【标准答案】（已完整实现，无需再填）。
 * 演示一个完整的工具实现，也作为你写其它工具的模板。
 * 面试时可拿它讲"工具从定义到执行"全流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryDocumentStatsTool implements Tool {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;

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
        // 数据隔离：只统计当前登录用户的文档（工具由 /api/agent/** 触发，JwtInterceptor 已保证有 userId）
        Long userId = UserContext.getUserId();

        long total = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>().eq(Document::getUserId, userId));
        // 已向量化：embedding_status = 1
        long embedded = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId)
                        .eq(Document::getEmbeddingStatus, 1));
        // 有内容分块的文档数（可作为"已处理"参考）
        // document_chunk 表没有 user_id 字段，用子查询限定当前用户的文档
        long withChunk = documentChunkMapper.selectCount(
                new LambdaQueryWrapper<DocumentChunk>()
                        .inSql(DocumentChunk::getDocumentId,
                                "select id from document where user_id = " + userId)
                        .isNotNull(DocumentChunk::getContent));

        log.info("[Tool:{}] 统计结果(userId={}): total={}, embedded={}, withChunk={}",
                name(), userId, total, embedded, withChunk);

        return "知识库共 " + total + " 篇文档，其中 " + embedded + " 篇已完成向量化，"
                + (total - embedded) + " 篇待处理；有内容分块的文档 " + withChunk + " 篇。";
    }
}
