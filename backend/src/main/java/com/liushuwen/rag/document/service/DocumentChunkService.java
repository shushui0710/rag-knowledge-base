package com.liushuwen.rag.document.service;

import com.liushuwen.rag.document.entity.DocumentChunk;
import com.liushuwen.rag.document.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分块服务：将长文本按滑动窗口切分为可向量化的语义片段并落库。
 * 位于解析与 Embedding 之间，分块质量直接决定检索粒度与召回效果。
 * 【设计要点】滑动窗口分块：512 字符窗口 + 64 字符重叠，步长 448，重叠保证边界句不被切断、跨块语义连续
 * 【常见问题】块太大/太小各有什么问题？——太大稀释向量语义、检索不精准；太小上下文不足、答案残缺；为什么用 @Value 而不是 @ConfigurationProperties？——单值注入写法简洁，一组相关配置才用配置属性类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentChunkService {

    private final DocumentChunkMapper documentChunkMapper;

    /**
     * 分块大小（字符数），从yml读取: rag.chunk-size，默认512
     */
    @Value("${rag.chunk-size:512}")
    private int chunkSize;

    /**
     * 分块重叠大小（字符数），从yml读取: rag.chunk-overlap，默认64
     */
    @Value("${rag.chunk-overlap:64}")
    private int chunkOverlap;

    /**
     * 分块并落库：切分文本后逐块写入 document_chunk 表，返回分块数。
     * 【设计要点】分块是 RAG 的第一个质量闸口：块粒度决定向量语义密度，直接影响召回精度
     * 【常见问题】这里为什么逐条 insert 而不是批量？——分块量小可接受，量大应换 saveBatch 或自定义批量 SQL 减少网络往返
     *
     * @param documentId 所属文档ID
     * @param text       要分块的文本内容
     * @return 分块数量
     */
    public int chunkAndSave(Long documentId, String text) {
        log.info("开始分块: documentId={}, textLength={}, chunkSize={}, overlap={}",
                documentId, text.length(), chunkSize, chunkOverlap);

        // 功能：滑动窗口切分文本｜要点：窗口 512 + 重叠 64，步长 448
        List<String> chunks = splitText(text);
        log.info("分块完成: documentId={}, chunkCount={}", documentId, chunks.size());

        // 功能：组装实体逐块入库｜要点：document_id+chunkIndex 建立原文与向量的映射
        for (int i = 0; i < chunks.size(); i++) {
            String chunkContent = chunks.get(i);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);           // 分块序号：0, 1, 2, ...
            chunk.setContent(chunkContent);    // 分块内容
            chunk.setCharCount(chunkContent.length());  // 字符数

            documentChunkMapper.insert(chunk);
        }

        return chunks.size();
    }

    /**
     * 滑动窗口分块算法：窗口 chunkSize、重叠 chunkOverlap，步长 = 窗口 - 重叠。
     * 相邻块共享 overlap 字符，落在边界处的关键信息至少能在一块中完整出现。
     * 【设计要点】为什么滑动窗口要重叠：语义可能正好被切块边界截断，重叠保证跨块语义连续、降低召回遗漏
     * 【常见问题】尾部残余怎么处理？——不足一个步长时收尾，剩余超过 overlap 才单独成块，避免与上一块高度重叠的碎片块
     *
     * @param text 要分块的文本
     * @return 分块列表
     */
    private List<String> splitText(String text) {
        // 功能：空文本直接返回｜要点：边界条件前置判断
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // 功能：短于窗口整块返回｜要点：避免无意义切分
        if (text.length() <= chunkSize) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;          // 当前块的起始位置
        int step = chunkSize - chunkOverlap;  // 每次前进的步长

        // 功能：按步长 448 滑动切块｜要点：步长 = 窗口 - 重叠
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());  // 结束位置（不超过文本末尾）
            String chunk = text.substring(start, end);
            chunks.add(chunk);

            // 功能：起点前移一个步长，与上一块保留 overlap 重叠
            start += step;

            // 功能：剩余文本不足一个步长时收尾｜要点：剩余超过 overlap 才单独成块，防止碎片块
            if (text.length() - start < step && start < text.length()) {
                // 功能：剩余文字与上一块重叠过多时舍弃｜要点：避免产生近重复的碎片块
                String remaining = text.substring(start);
                if (remaining.length() > chunkOverlap) {
                    chunks.add(remaining);
                }
                break;  // 剩余处理完了，退出循环
            }
        }

        return chunks;
    }
}
