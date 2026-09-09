package com.liushuwen.rag.rag;

import lombok.Data;

/**
 * 评审结果 DTO：承载 LLM-as-Judge 的判定结论，是 CriticService 与编排层之间的契约。
 * 【设计要点】结构化评审契约：pass 决定放行/重写，reason 作为重写提示喂回生成，形成自纠错闭环
 * 【常见问题】reason 在非 JSON 解析失败时为空会不会丢信息？——此时已默认放行，无需 reason，重写循环不会触发
 */
@Data
public class Critique {

    // 功能：评审是否通过｜要点：布尔判定驱动主流程"放行 or 重写"
    private boolean pass;

    // 功能：不合格原因/改进建议，供 LLM 重写参考｜要点：反馈信号闭环
    private String reason;

    public static Critique pass() {
        Critique c = new Critique();
        c.setPass(true);
        c.setReason("");
        return c;
    }

    public static Critique fail(String reason) {
        Critique c = new Critique();
        c.setPass(false);
        c.setReason(reason);
        return c;
    }
}
