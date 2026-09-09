package com.liushuwen.rag.common;

import lombok.Getter;

/**
 * 业务异常：继承 RuntimeException 并携带业务错误码，交由全局异常处理器统一转换。
 * 【设计要点】受检 vs 非受检异常：继承 RuntimeException 属非受检，业务错误无需强制 try-catch，由 @RestControllerAdvice 集中兜底
 * 【常见问题】为何默认 code=400？——业务校验类错误归为客户端错误；如何区分不同业务错误？——构造时显式传 code（如 400/409），而非仅靠 message
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }
}
