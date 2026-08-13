package com.liushuwen.rag.rag;

import lombok.Data;

/**
 * 回答质量评审结果（阶段4-反思）
 */
@Data
public class Critique {

    /** 是否合格 */
    private boolean pass;

    /** 评审意见/改进建议（不合格时给 LLM 重写用） */
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
