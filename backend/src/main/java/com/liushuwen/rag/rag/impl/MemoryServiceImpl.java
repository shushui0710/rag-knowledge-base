package com.liushuwen.rag.rag.impl;

import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusMemoryStore;
import com.liushuwen.rag.rag.MemoryService;
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
    private final MilvusMemoryStore milvusMemoryStore;

    // 功能：记忆召回最低相似度阈值（>0.5 才注入）｜要点：相似度过滤防低相关噪声污染上下文
    private static final float MEMORY_MIN_SCORE = 0.5f;

    @Override
    public void saveExchange(Long userId, String question, String answer) {
        try {
            if (question == null || answer == null) {
                return;                                   // 空值防御：参数缺失直接跳过，不写脏数据
            }
            // 【缺陷修复·向量与入库文本不同源】原实现把"截断到 200 字"的结果只用于算向量，入库却传了
            // **完整 answer**（String qa = ... ; insertMemory(vec, userId, question, answer)）：
            //   ① 注释宣称的"截断防膨胀、控单条记忆体积"对存储侧根本没生效 —— 这正是"超长内容静默丢失"
            //      （Milvus code=1100）的上游根因，长回答得以直接撞 2048 字节字段上限；
            //   ② 向量只反映"问题 + 前 200 字"，content 却存全文 ⇒ 二者语义错位，>200 字回答的后半段
            //      对相似度贡献为零。
            // 修法：先把记忆文本按 **UTF-8 字节上限**（与 Milvus schema 同一常量）裁剪一次，
            // 再让 embedding 与入库**共用这一份文本**。上限取 2048 字节而非 200 字：embedding-3 支持
            // 8192 tokens 输入，2048 字节（≈682 汉字）远未触顶，保留更完整的上下文更利于召回。
            String qa = MilvusMemoryStore.truncateUtf8(question + "\n" + answer,
                    MilvusMemoryStore.MEMORY_CONTENT_MAX_LEN);
            List<float[]> vecs = embeddingService.embed(List.of(qa));
            if (vecs == null || vecs.isEmpty()) {
                return;
            }
            milvusMemoryStore.insertMemory(vecs.get(0), userId, qa);
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
            return milvusMemoryStore.searchMemory(vecs.get(0), 3, userId).stream()
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
