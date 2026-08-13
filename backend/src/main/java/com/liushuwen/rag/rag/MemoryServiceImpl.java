package com.liushuwen.rag.rag;

import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆实现（阶段4 ✅ 已实现）
 *
 * 链路：高质量问答对 → 向量化 → 存入 qa_memory（独立 collection，见 MilvusService）。
 * 记忆是旁路增强：保存失败只记日志、召回失败返回空列表，绝不影响问答主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;

    /** 记忆召回的最低相似度阈值（防止低相关记忆注入 Prompt） */
    private static final float MEMORY_MIN_SCORE = 0.5f;

    /** 回答存储上限（字符），防记忆膨胀 */
    private static final int ANSWER_MAX_LEN = 200;

    @Override
    public void saveExchange(String question, String answer) {
        try {
            if (question == null || answer == null) {
                return;                                   // 空值防御
            }
            String qa = question + "\n" + (answer.length() > ANSWER_MAX_LEN
                    ? answer.substring(0, ANSWER_MAX_LEN) : answer);   // 截断防膨胀
            List<float[]> vecs = embeddingService.embed(List.of(qa));
            if (vecs == null || vecs.isEmpty()) {
                return;
            }
            milvusService.insertMemory(vecs.get(0), question, answer);
        } catch (Exception e) {
            // ⚠️ 记忆保存失败绝不能阻断问答主流程：只记日志（面试亮点：容错降级）
            log.warn("长期记忆保存失败（不影响本次回答）: {}", e.getMessage());
        }
    }

    @Override
    public List<String> recall(String question) {
        try {
            if (question == null) {
                return List.of();                         // 空值防御
            }
            List<float[]> vecs = embeddingService.embed(List.of(question));
            if (vecs == null || vecs.isEmpty()) {
                return List.of();
            }
            return milvusService.searchMemory(vecs.get(0), 3).stream()
                    .filter(h -> h.getScore() > MEMORY_MIN_SCORE)       // 阈值防低相关
                    .map(h -> {
                        // content 存的是 "问题\n回答"，拆成可读格式
                        String c = h.getContent() == null ? "" : h.getContent();
                        int idx = c.indexOf('\n');
                        String q = idx > 0 ? c.substring(0, idx) : c;
                        String a = idx > 0 ? c.substring(idx + 1) : "";
                        return "Q:" + q + "\nA:" + a;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // ⚠️ 记忆召回失败：返回空列表（等同"没有记忆"），不抛异常
            log.warn("长期记忆召回失败（按无记忆处理）: {}", e.getMessage());
            return List.of();
        }
    }
}
