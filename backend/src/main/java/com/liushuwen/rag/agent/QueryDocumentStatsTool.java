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

import java.util.List;
import java.util.Map;

/**
 * 工具：文档统计查询，返回当前用户文档总数、向量化/未向量化数量、分类统计。
 * 【设计要点】MyBatis-Plus 聚合查询 + 用户数据隔离：eq(userId) 限定范围，统计结果裁剪后回传 LLM
 * 【常见问题】为什么工具结果要裁剪/脱敏？——只回传 LLM 决策所需信息，控制 token 与避免泄露
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
        // 有内容分块的【文档】数：必须按 document_id 去重后计数。
        // 【缺陷修复·统计口径】原实现是 selectCount(...isNotNull(content))，统计的是 document_chunk 的
        // 【行数】；而 512/64 的滑窗会把一篇文档切成多块，于是该数字必然 ≥ 文档总数，出现
        // 「共 1 篇文档，有内容分块的文档 2 篇」这种自相矛盾的统计（Agent 还专门把该矛盾当异常报给用户）。
        // 正确做法是按 document_id 分组去重——GROUP BY 在 SQL 侧完成，Java 侧拿到的就是「文档数」。
        // 【同时去掉手写 SQL】原实现用 inSql 拼原生子查询（并把 userId 直接拼进 SQL 字符串），
        // 改为「先取当前用户文档 id → 再 in 过滤」，既保留「逻辑已删除文档不计入」的语义，
        // 又不必依赖对 MyBatis-Plus 逻辑删除插件改写范围的推断。
        List<Long> docIds = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                        .select(Document::getId)
                        .eq(Document::getUserId, userId))
                .stream().map(Document::getId).toList();
        // document_chunk 无 user_id 字段，靠 document_id 归属；docIds 为空时 MP 会生成
        // 非法的 IN () ，因此必须短路
        long withChunk = docIds.isEmpty() ? 0L : documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .select(DocumentChunk::getDocumentId)
                        .in(DocumentChunk::getDocumentId, docIds)
                        .isNotNull(DocumentChunk::getContent)
                        .groupBy(DocumentChunk::getDocumentId)).size();

        log.info("[Tool:{}] 统计结果(userId={}): total={}, embedded={}, withChunk={}",
                name(), userId, total, embedded, withChunk);

        return "知识库共 " + total + " 篇文档，其中 " + embedded + " 篇已完成向量化，"
                + (total - embedded) + " 篇待处理；有内容分块的文档 " + withChunk + " 篇。";
    }
}
