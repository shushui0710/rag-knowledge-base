package com.liushuwen.rag.document.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 运维台状态：一次请求同时回答"我有没有运维权限"和"当前任务进展如何"。
 * 【设计要点】把权限判定与任务状态合并成一个只读视图，前端只需一次请求即可决定
 *   "要不要显示运维按钮"与"要不要显示进度条"，无需另开鉴权接口或在前端复制白名单逻辑。
 * 【常见问题】为什么不是 Map&lt;String,Object&gt;？——Map.of() 不接受 null 值，
 *   而"没有任务"时 task 恰为 null，用 Map 会踩坑；固定 DTO 天然支持 null 字段且类型清晰。
 */
@Getter
@Builder(toBuilder = true)
@ToString
public class IndexRebuildStatus {

    /** 当前账号是否在运维白名单内（false 时 task 恒为 null，不泄露任务细节） */
    private final boolean allowed;

    /** 最近一次重建任务快照；无任务或无权查看时为 null */
    private final IndexRebuildTask task;
}
