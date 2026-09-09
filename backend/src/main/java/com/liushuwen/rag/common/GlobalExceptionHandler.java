package com.liushuwen.rag.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：用 @RestControllerAdvice 统一拦截并转换为 Result。
 * 【设计要点】@RestControllerAdvice：集中捕获 Controller 层异常，业务异常返回 400、兜底返回 500，避免异常堆栈泄漏到前端
 * 【常见问题】@ControllerAdvice 与 @RestControllerAdvice 区别？——后者自带 @ResponseBody 直接返回 JSON；为何校验失败返回 400？——参数不合法属客户端错误，需快速失败
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    // 功能：捕获 @Valid 参数校验失败（MethodArgumentNotValidException），聚合字段错误返回 400｜要点：校验前置拦截，减少业务层判空
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Result.error(400, message);
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    // 功能：捕获业务异常，透传其 code 与 message 返回 400｜要点：业务异常与系统异常分级处理
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    // 功能：兜底捕获其余未预期异常，返回 500 且只给模糊提示｜要点：异常隔离——细节留日志，对外不泄露内部信息
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: ", e);
        return Result.error(500, "系统内部错误，请稍后重试");
    }
}
