package com.liushuwen.rag.document.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 索引重建任务的只读快照：运维接口的对外契约（受理响应与状态查询共用）。
 * 【设计要点】不可变 + toBuilder：后台线程每推进一步就产出一个新快照原子替换，
 *   读取方拿到的永远是"某一时刻完整自洽的状态"，不存在读到半更新字段的撕裂问题
 *   （对比：可变对象被后台线程原地写入，状态接口可能读到 processedDocs 已更新而 totalDocs 还是旧值）。
 * 【常见问题】为什么不用实体类/Map？——它既不是数据库实体也不是自由结构，
 *   显式字段同时充当接口文档（Swagger 能展开），比 Map 更利于前端与联调。
 *   用 @Getter + @Builder 而非 @Data：字段全 final 时 @Data 的构造器会与 @Builder 的
 *   全参构造器签名冲突（全参 = 必需参数），编译报"重复构造器"。
 */
@Getter
@Builder(toBuilder = true)
@ToString
public class IndexRebuildTask {

    /** 运行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 已完成 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 已失败（MySQL 仍是事实源，修复后可重试） */
    public static final String STATUS_FAILED = "FAILED";

    /** 任务号（形如 rebuild-1758333333333），用于日志串联与前端轮询比对 */
    private final String taskId;

    /** 任务状态：RUNNING / SUCCESS / FAILED */
    private final String status;

    /** 当前阶段（人话）：已受理 → 规划完成 → 回放中 → 完成 / 失败 */
    private final String phase;

    /** 操作人用户名（审计用，来自 JWT + 白名单校验后的账号） */
    private final String operator;

    /** 本次是否走了"删旧重建"路径（true = 旧结构不支持 BM25，需影子表切换） */
    private final boolean needDrop;

    /** 待回放文档总数（事实源 MySQL 中 embedding_status=1 的文档数） */
    private final int totalDocs;

    /** 已处理文档数（含无分块被跳过的） */
    private final int processedDocs;

    /** 已回放文档数（真正重新向量化并写入的） */
    private final int replayedDocs;

    /** 开始时间（yyyy-MM-dd HH:mm:ss） */
    private final String startedAt;

    /** 结束时间；未结束时为 null */
    private final String finishedAt;

    /** 已耗时（ms），运行中实时刷新 */
    private final long elapsedMillis;

    /** 说明/失败原因：面向运维的可读描述 */
    private final String message;
}
