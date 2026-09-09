package com.liushuwen.rag.common;

import lombok.Data;

/**
 * 统一响应体：封装 code / message / data，规范所有接口的返回结构。
 * 【设计要点】统一响应契约：前后端约定固定结构，前端按 code 分支处理，避免各接口各自定义返回格式
 * 【常见问题】成功/失败 code 怎么定？——成功 200、业务异常取异常自带 code（默认 400）、兜底 500；为何用静态工厂？——集中构造、保证字段一致、调用处更语义化
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> success() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }
}
