package com.liushuwen.rag.rag;

import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆实现：问答对向量化写入 qa_memory（独立 collection），召回时按 user_id 过滤、按相似度阈值取 Top3 注入。
 * 【设计要点】记忆回存-召回闭环：写入带 user_id 隔离、召回超 0.5 取 Top3 注入；保存/召回异常均静默降级保主流程
 * 【常见问题】为什么召回阈值取 0.5、取 Top3？——阈值挡低相关噪声，Top3 控制上下文长度与成本；常见问题：记忆写入要不要质量门槛？→ 本实现依赖上游只存高质量问答，并可由编排层在入库前加质量门防噪声
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;

    // 功能：记忆召回最低相似度阈值（>0.5 才注入）｜要点：相似度过滤防低相关噪声污染上下文
    private static final float MEMORY_MIN_SCORE = 0.5f;

    // 功能：回答存储字符上限（200）｜要点：截断防单条记忆膨胀、控向量维度成本
    private static final int ANSWER_MAX_LEN = 200;

    @Override
    public void saveExchange(Long userId, String question, String answer) {
        try {
            if (question == null || answer == null) {
                return;                                   // 空值防御：参数缺失直接跳过，不写脏数据
            }
            String qa = question + "\n" + (answer.length() > ANSWER_MAX_LEN
                    ? answer.substring(0, ANSWER_MAX_LEN) : answer);   // 截断防膨胀：控制单条记忆体积
            List<float[]> vecs = embeddingService.embed(List.of(qa));
            if (vecs == null || vecs.isEmpty()) {
                return;
            }
            milvusService.insertMemory(vecs.get(0), userId, question, answer);
        } catch (Exception e) {
            // 功能：记忆保存失败只记日志、不抛异常｜要点：旁路增强 fail-safe（记忆丢一条不影响本次回答）
            log.warn("长期记忆保存失败（不影响本次回答）: {}", e.getMessage());
        }
    }

    @Override
    public List<String> recall(Long userId, String question) {
        try {
            if (question == null) {
                return List.of();                         // 空值防御：问题为空视为无记忆可召回
            }
            List<float[]> vecs = embeddingService.embed(List.of(question));
            if (vecs == null || vecs.isEmpty()) {
                return List.of();
            }
            return milvusService.searchMemory(vecs.get(0), 3, userId).stream()
                    .filter(h -> h.getScore() > MEMORY_MIN_SCORE)       // 阈值防低相关：仅超 0.5 的历史记忆注入上下文
                    .map(h -> {
                        // 功能：content 存的是"问题\n回答"，拆成 Q:/A: 可读格式注入 Prompt｜要点：存储格式与展示格式解耦
                        String c = h.getContent() == null ? "" : h.getContent();
                        int idx = c.indexOf('\n');
                        String q = idx > 0 ? c.substring(0, idx) : c;
                        String a = idx > 0 ? c.substring(idx + 1) : "";
                        return "Q:" + q + "\nA:" + a;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 功能：记忆召回失败返回空列表（等同无记忆）｜要点：fail-open 降级（召回挂了就当没记忆，主流程照常）
            log.warn("长期记忆召回失败（按无记忆处理）: {}", e.getMessage());
            return List.of();
        }
    }
}
