package com.liushuwen.rag.document.service.impl;

import com.liushuwen.rag.auth.entity.User;
import com.liushuwen.rag.auth.service.UserService;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.document.dto.IndexRebuildStatus;
import com.liushuwen.rag.document.dto.IndexRebuildTask;
import com.liushuwen.rag.document.service.IndexRebuildService;
import com.liushuwen.rag.document.service.IndexRebuildWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 索引运维服务实现：做"准入 + 受理"，把真正干活的部分交给后台执行器。
 *
 * 职责边界刻意收窄：本类只回答三个问题——你有没有权限、你有没有确认、现在有没有任务在跑；
 * 一旦受理成功就立刻返回，长任务全部在 IndexRebuildWorker 里。
 *
 * 【设计要点】安全边界在服务端：角色命中 ADMIN 才放行，前端隐藏按钮只是体验优化。
 *   准入判据用"落库的角色"而不是"配置里的用户名白名单"——本系统注册开放，
 *   按用户名白名单会被"抢先注册同名账号"绕过；角色只能由运维在库侧提权。
 * 【常见问题】为什么权限校验不放在 Controller？——Controller 只做协议接转是本项目既有约定；
 *   更重要的是"准入规则"属于业务约束（谁能运维），放服务层才能被复用与单测覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexRebuildServiceImpl implements IndexRebuildService {

    private final IndexRebuildWorker indexRebuildWorker;
    private final UserService userService;

    /**
     * 运维台状态：把"是否 ADMIN"与"任务快照"一次性返回。
     * 【设计要点】无权者拿不到任务细节（task 置 null），避免用状态接口侧漏运维信息
     * 【常见问题】为什么这里不抛异常？——这是页面初始化就要调的只读接口，
     *   抛错会让普通用户在文档页看到红色报错；"无权"是正常状态，用数据表达而非异常表达
     */
    @Override
    public IndexRebuildStatus status() {
        User me = currentUserOrNull();
        boolean allowed = isOpsAdmin(me);
        return IndexRebuildStatus.builder()
                .allowed(allowed)
                .task(allowed ? indexRebuildWorker.latest() : null)
                .build();
    }

    @Override
    public IndexRebuildTask submit(String confirm) {
        User operator = requireOpsAdmin();

        // 功能：二次确认闸门——必须原样回填确认串｜要点：破坏性操作不能靠"点了一下"就触发
        if (!CONFIRM_TOKEN.equals(confirm)) {
            throw new BusinessException("二次确认失败：请求体需携带 {\"confirm\":\"" + CONFIRM_TOKEN + "\"}");
        }

        String taskId = "rebuild-" + System.currentTimeMillis();
        IndexRebuildTask accepted = indexRebuildWorker.begin(taskId, operator.getUsername());
        log.warn("[运维审计] 索引重建受理 taskId={} 操作人={}(userId={})", taskId,
                operator.getUsername(), operator.getId());

        // 功能：丢进运维专用线程池执行（跨 Bean 调用才会走 @Async 代理）｜要点：接口立即返回，不再阻塞 72s
        indexRebuildWorker.runAsync(taskId, operator.getUsername());
        return accepted;
    }

    // ==================== 准入判定 ====================

    /**
     * 强制要求运维权限：不满足直接抛 403（业务码），由全局异常处理器统一转 Result。
     * 【设计要点】失败信息带上当前账号名，便于本人自查"我为什么没有权限"
     * 【常见问题】为什么 HTTP 层仍是 400？——本项目业务异常统一以 HTTP 400 + 业务码返回
     *   （见 A6-05 固化的既有契约）；精确语义（无权限 vs 参数错）靠 business code 区分，
     *   不为此单独改全局异常出口，避免影响既有前端处理逻辑
     */
    private User requireOpsAdmin() {
        User me;
        try {
            me = userService.getCurrentUser();
        } catch (BusinessException e) {
            throw new BusinessException(403, "请先登录后再执行运维操作");
        }
        if (!isOpsAdmin(me)) {
            throw new BusinessException(403, "无权限执行运维操作：索引重建会重建全库向量，仅 ADMIN 角色可用。"
                    + "当前账号：" + (me == null ? "未知" : me.getUsername())
                    + "（角色：" + (me == null ? "-" : me.getRole()) + "）");
        }
        return me;
    }

    /**
     * 运维权限判定：角色为 ADMIN 才放行（大小写不敏感）。
     * 【设计要点】fail-closed：角色缺失/为空/非 ADMIN 一律视为无权限，避免"迁移没跑全"
     *   或脏数据把破坏性操作意外放开
     * 【常见问题】为什么不用"配置里的用户名白名单"？——本系统注册开放，
     *   按用户名匹配会被"抢先注册同名账号"绕过；角色是落库的身份事实，只能由运维在库侧提权
     */
    private boolean isOpsAdmin(User user) {
        return user != null && user.getRole() != null
                && User.ROLE_ADMIN.equalsIgnoreCase(user.getRole().trim());
    }

    /** 取当前登录用户；未登录（无 token / token 失效）返回 null 而非抛异常，供只读状态接口使用 */
    private User currentUserOrNull() {
        try {
            return userService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }
}
