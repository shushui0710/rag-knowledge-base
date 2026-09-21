package com.liushuwen.rag.document.service;

import com.liushuwen.rag.document.dto.IndexRebuildStatus;
import com.liushuwen.rag.document.dto.IndexRebuildTask;

/**
 * 索引运维服务契约：索引重建的准入（白名单 + 二次确认）、异步受理与状态查询。
 * 与 DocumentService 分开：文档服务管"业务数据"，本服务管"运维动作"，
 * 破坏性的全局操作不该混进日常业务契约里。
 * 【设计要点】把运维能力单独成接口，便于测试直接注入替身，也便于将来把运维端点整体摘到独立运维通道
 * 【常见问题】为什么这里不暴露"同步版重建"？——72s 的同步阻塞请求线程已被实测证明是缺陷，
 *   保留同步入口只会诱使调用方再次踩坑
 */
public interface IndexRebuildService {

    /**
     * 二次确认串：提交重建时必须原样回填，用于拦住"误点按钮/误发请求"这类事故。
     * 它不是密钥（不承担鉴权职责，鉴权由白名单负责），而是一道"确认你确实知道自己在干什么"的闸门。
     */
    String CONFIRM_TOKEN = "CONFIRM-REBUILD";

    /** 运维台状态：当前账号是否可运维 + 最近一次任务进展 */
    IndexRebuildStatus status();

    /**
     * 受理一次索引重建：校验运维权限与确认串 → 登记任务 → 丢后台执行。
     * 立即返回任务快照（不再阻塞请求线程），后续进展由 status() 轮询。
     *
     * @param confirm 二次确认串，必须等于 {@link #CONFIRM_TOKEN}
     * @return 刚受理的任务快照（status=RUNNING）
     */
    IndexRebuildTask submit(String confirm);
}
