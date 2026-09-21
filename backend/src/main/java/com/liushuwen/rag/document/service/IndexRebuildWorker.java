package com.liushuwen.rag.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.document.dto.IndexRebuildTask;
import com.liushuwen.rag.document.entity.Document;
import com.liushuwen.rag.document.entity.DocumentChunk;
import com.liushuwen.rag.document.mapper.DocumentChunkMapper;
import com.liushuwen.rag.document.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static com.liushuwen.rag.config.AsyncConfig.OPS_EXECUTOR;

/**
 * 索引重建执行器：把"删旧→重建→回放"这条分钟级长任务挪到后台线程跑，并按步产出状态快照。
 *
 * 【为什么单独一个类】@Async 依赖 Spring 代理生效，同类内部自调用会绕过代理导致"异步不生效"；
 *   因此把异步方法放在独立 Bean 里，由 IndexRebuildServiceImpl 从外部调用，代理才会介入。
 *
 * 【为什么状态放在这里】任务状态与执行过程强相关（谁能改谁负责），放在执行器里
 *   让"写入方唯一"，服务层只做准入与读取，避免两处同时改同一份状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexRebuildWorker {

    /** 向量数据面的读写（删旧向量 / 插新向量） */
    private final MilvusService milvusService;

    /** 集合结构面：探测是否已升级 BM25、建表/删表、影子表改名切换 */
    private final MilvusCollectionManager milvusCollectionManager;

    private final EmbeddingService embeddingService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 最近一次任务快照：不可变对象 + 原子替换，读取方永远拿到自洽的某一时刻 */
    private final AtomicReference<IndexRebuildTask> latest = new AtomicReference<>();

    /**
     * 受理占位：登记一个 RUNNING 任务并返回快照（同步执行，供接口立即响应）。
     * 【设计要点】synchronized 把"检查是否已有任务"与"登记新任务"合成一个原子段，
     *   否则两次并发提交可能同时通过检查、双双开跑（破坏性操作叠加会互相删表）
     * 【常见问题】为什么用 409 而不是 400？——语义上属于"资源状态冲突"，
     *   接口虽是 HTTP 400（项目统一异常出口），但业务码 409 让前端能精准区分"稍后重试"与"参数错误"
     */
    public synchronized IndexRebuildTask begin(String taskId, String operator) {
        IndexRebuildTask current = latest.get();
        if (current != null && IndexRebuildTask.STATUS_RUNNING.equals(current.getStatus())) {
            throw new BusinessException(409, "已有索引重建任务在执行中（taskId=" + current.getTaskId()
                    + "，操作人=" + current.getOperator() + "），请等待其结束后再提交");
        }
        IndexRebuildTask task = IndexRebuildTask.builder()
                .taskId(taskId)
                .status(IndexRebuildTask.STATUS_RUNNING)
                .phase("已受理")
                .operator(operator)
                .needDrop(false)
                .totalDocs(0)
                .processedDocs(0)
                .replayedDocs(0)
                .startedAt(now())
                .finishedAt(null)
                .elapsedMillis(0)
                .message("任务已进入后台队列，可轮询本接口查看进度")
                .build();
        latest.set(task);
        return task;
    }

    /** 最近一次任务快照（可能为 null = 从未执行过） */
    public IndexRebuildTask latest() {
        return latest.get();
    }

    /**
     * 后台重建索引（@Async：调用方必须是本 Bean 之外的调用，才会经过代理）。
     *
     * 事实源是 MySQL：document_chunk 里存着全部分块原文，Milvus 只是"可重建的索引层"，
     * 因此整条链路可以反复重跑而不会丢数据。
     *
     * 两条路径：
     *   ① 结构已就绪（collection 已有 bm25_vector）⇒ **原地逐文档重灌**：
     *      集合始终存在，每个文档"先删旧向量再插新向量"，全程零索引真空期。
     *   ② 结构待升级（旧 collection 无 BM25）⇒ **影子表 + 改名切换**：
     *      先按新结构建影子表并完成全量回放（此期间旧表照常对外服务），
     *      最后释放/删除旧表、把影子表改名为正式名、重新加载 —— 不可用窗口只剩改名这几毫秒。
     *      对比修复前"先 drop 再回放"，真空期从整轮回放时长（实测 72s）压缩到毫秒级。
     * 失败时的兜底不变式：MySQL 分块仍在 ⇒ 重试本接口即可收敛（两路径前缀步都幂等）。
     */
    @Async(OPS_EXECUTOR)
    public void runAsync(String taskId, String operator) {
        long start = System.currentTimeMillis();
        try {
            // ── 第 1 步：规划。先定清单（事实源），再探测 Milvus 结构决定路径 ──
            List<Document> docs = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                    .eq(Document::getEmbeddingStatus, 1)
                    .orderByAsc(Document::getId));
            boolean needDrop = !milvusCollectionManager.mainCollectionHasBm25();
            final boolean coldPath = needDrop;
            update(taskId, t -> t.toBuilder()
                    .phase("规划完成")
                    .needDrop(coldPath)
                    .totalDocs(docs.size())
                    .message(coldPath
                            ? "旧 collection 无 BM25 结构：先建影子表回放（旧表继续服务），再改名切换"
                            : "现有 collection 已支持 BM25：原地逐文档重灌，无索引真空期")
                    .build());
            log.warn("[运维] 索引重建开始 taskId={} operator={} 路径={} 待回放文档={}",
                    taskId, operator, coldPath ? "影子表切换" : "原地重灌", docs.size());

            // ── 第 2 步：准备目标 collection（幂等）──
            String target = coldPath ? milvusCollectionManager.shadowCollectionName()
                    : milvusCollectionManager.mainCollectionName();
            if (coldPath) {
                milvusCollectionManager.dropCollectionIfExists(target);   // 清掉上轮可能残留的影子表
                milvusCollectionManager.createHybridCollection(target);
            } else {
                milvusCollectionManager.createHybridCollection();
            }

            // ── 第 3 步：逐文档回放（先删旧向量再插新，避免主键重复）──
            int processed = 0;
            int replayed = 0;
            for (Document doc : docs) {
                List<DocumentChunk> chunks = documentChunkMapper.selectList(
                        new LambdaQueryWrapper<DocumentChunk>()
                                .eq(DocumentChunk::getDocumentId, doc.getId())
                                .orderByAsc(DocumentChunk::getChunkIndex));
                if (!chunks.isEmpty()) {
                    milvusService.deleteByDocumentId(target, doc.getId());
                    List<String> texts = chunks.stream().map(DocumentChunk::getContent).toList();
                    List<float[]> vectors = embeddingService.embed(texts);
                    List<Long> chunkIds = chunks.stream().map(DocumentChunk::getId).toList();
                    milvusService.insertVectors(target, chunkIds, doc.getId(), texts, vectors);
                    replayed++;
                }
                processed++;
                final int done = processed;
                final int doneReplay = replayed;
                update(taskId, t -> t.toBuilder()
                        .phase("回放中")
                        .processedDocs(done)
                        .replayedDocs(doneReplay)
                        .elapsedMillis(System.currentTimeMillis() - start)
                        .build());
            }

            // ── 第 4 步：冷升级路径的切换（耗时回放已结束，此刻才动旧表）──
            if (coldPath) {
                milvusCollectionManager.promoteShadowToMain();
            }

            final int total = docs.size();
            final int replayFinal = replayed;
            update(taskId, t -> t.toBuilder()
                    .status(IndexRebuildTask.STATUS_SUCCESS)
                    .phase("完成")
                    .processedDocs(total)
                    .replayedDocs(replayFinal)
                    .finishedAt(now())
                    .elapsedMillis(System.currentTimeMillis() - start)
                    .message("重建完成：回放 " + replayFinal + " 个文档"
                            + (coldPath ? "（含影子表切换，回放期间检索未中断）" : "（原地重灌，全程未中断）"))
                    .build());
            log.warn("[运维] 索引重建完成 taskId={} 回放文档={} 耗时={}ms",
                    taskId, replayFinal, System.currentTimeMillis() - start);

        } catch (Exception e) {
            // 不向外抛：@Async void 的异常无人接收，统一落状态并留日志
            log.error("[运维] 索引重建失败 taskId={}: {}", taskId, e.getMessage(), e);
            update(taskId, t -> t.toBuilder()
                    .status(IndexRebuildTask.STATUS_FAILED)
                    .phase("失败")
                    .finishedAt(now())
                    .elapsedMillis(System.currentTimeMillis() - start)
                    .message("重建失败：" + e.getMessage()
                            + "（分块仍在 MySQL，属可重建数据；修复后重试本接口即可）")
                    .build());
        }
    }

    /** 按任务号原子替换快照；任务号不匹配说明已被更新一代任务取代，则不动 */
    private void update(String taskId, UnaryOperator<IndexRebuildTask> mutator) {
        latest.updateAndGet(current ->
                current != null && taskId.equals(current.getTaskId()) ? mutator.apply(current) : current);
    }

    private String now() {
        return LocalDateTime.now().format(TIME);
    }
}
