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
 * 文档分块服务 - 将长文本按固定长度切分成小块
 *
 * 讲解要点：
 * 1. 为什么分块是RAG的核心环节？
 *    大模型每次只能处理有限长度的输入（DeepSeek约32K tokens）
 *    如果把整篇文档丢给模型，超出限制就无法处理
 *    分块后只检索最相关的几块，既减少输入长度又提高精度
 *
 * 2. @Value("${rag.chunk-size}") = 从application.yml读取配置值
 *    yml里 rag.chunk-size: 512 → 注入到 chunkSize 字段
 *    这和@ConfigurationProperties的区别：
 *    - @Value：适合读单个值，写法简洁
 *    - @ConfigurationProperties：适合读一组相关值（如MinIO的endpoint+key+bucket）
 *
 * 3. 分块策略：
 *    滑动窗口（Sliding Window）— 每次前进 chunkSize-overlap 步
 *    overlap=64 字重叠，防止关键信息被切断在两块边界
 *
 *    白话比喻：用一把512字的尺子量文本，每量完一次往前挪512-64=448字
 *    新的起点和上一块末尾有64字重叠，确保边界处的信息不会丢失
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
     * 将文本分块并存储到MySQL
     *
     * 流程：
     * 1. 调用splitText()把文本切成多块
     * 2. 为每块创建DocumentChunk实体
     * 3. 批量插入到document_chunk表
     * 4. 返回分块数量
     *
     * @param documentId 所属文档ID
     * @param text       要分块的文本内容
     * @return 分块数量
     */
    public int chunkAndSave(Long documentId, String text) {
        log.info("开始分块: documentId={}, textLength={}, chunkSize={}, overlap={}",
                documentId, text.length(), chunkSize, chunkOverlap);

        // Step 1: 文本分块
        List<String> chunks = splitText(text);
        log.info("分块完成: documentId={}, chunkCount={}", documentId, chunks.size());

        // Step 2: 创建实体并批量保存
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
     * 滑动窗口分块算法
     *
     * 演示（假设chunkSize=10, overlap=3）：
     * 原文："ABCDEFGHIJKLMNO..."（假设15字）
     *
     * Chunk 0: ABCDEFGHIJ  （第0-9字）
     * 前进步长 = chunkSize - overlap = 10 - 3 = 7
     * Chunk 1: HIJKLMNO... （第7字开始，取10字）
     *   ↑ HIJ 和上一个块的 GHIJ 重叠了3字！
     *
     * 这样如果"重要信息"刚好在HIJ附近，
     * Chunk 0里有GHIJ，Chunk 1里有HIJKLMN，
     * 至少有一块能完整包含这个信息
     *
     * @param text 要分块的文本
     * @return 分块列表
     */
    private List<String> splitText(String text) {
        // 空文本或短文本不需要分块
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // 文本比一块还短，直接整块返回
        if (text.length() <= chunkSize) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;          // 当前块的起始位置
        int step = chunkSize - chunkOverlap;  // 每次前进的步长

        // 循环分块，直到文本末尾
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());  // 结束位置（不超过文本末尾）
            String chunk = text.substring(start, end);
            chunks.add(chunk);

            // 前进到下一个块的起始位置
            start += step;

            // 如果剩下的文本太短（不足一个步长），就作为最后一块
            // 防止产生大量极短的碎片块
            if (text.length() - start < step && start < text.length()) {
                // 取从start到末尾的所有剩余文字
                // 但注意：如果剩余文字和上一块重叠太多，就不单独成块了
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
