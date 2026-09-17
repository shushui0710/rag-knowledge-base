package com.liushuwen.rag.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    // 功能：捕获请求体解析失败（JSON 语法非法 / 字段类型不匹配 / 收到裸字符串等），返回 400｜要点：请求体不合法属于客户端错误，
    // 修复前会落到 Exception 兜底返回 500，把"用户发错格式"误报成"服务端故障"，掩盖真实契约问题也污染监控
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(400, "请求体格式错误");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    // 功能：捕获 multipart 体积超限，返回 413 并明确提示上限｜要点：体积超限属客户端错误，不能落到 500 兜底
    // 【缺陷修复】修复前该异常未被单独处理，直接落到下方 Exception 兜底返回 HTTP 500「系统内部错误」，
    // 把「用户传了过大的文件」误报成「服务端故障」——既误导排查方向，也污染服务端错误率监控。
    // 这正是 handleNotReadable 那条注释想避免的问题，体积超限是同类漏网路径。
    public Result<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("上传体积超限: {}", e.getMessage());
        return Result.error(413, "上传文件过大，单个文件不得超过 50MB");
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    // 功能：兜住其余 multipart 解析异常（如请求体不是合法 multipart），归为客户端错误返回 400
    // 【设计要点】MaxUploadSizeExceededException 是 MultipartException 的子类，Spring 会优先匹配更具体的那个
    public Result<Void> handleMultipart(MultipartException e) {
        log.warn("multipart 解析失败: {}", e.getMessage());
        return Result.error(400, "文件上传请求格式错误");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    // 功能：捕获「静态资源 / 路径不存在」（如 /favicon.ico、拼错的任意路径），返回 404 Not Found
    // 【缺陷修复】修复前该异常被下方 Exception 兜底吞成 HTTP 500「系统内部错误」：
    // 把「客户端请求了不存在的资源」误报成「服务端故障」——既污染服务端错误率监控、误导排查方向，
    // 也让 Knife4j（/doc.html）加载 /favicon.ico 时在前端控制台刷出 500。按 REST 语义，404 才是正确回应。
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return Result.error(404, "请求的资源不存在");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    // 功能：捕获「未匹配到任何处理器」的请求（开启 throw-exception-if-no-handler-found 时生效），返回 404
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("接口不存在: {} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.error(404, "请求的接口不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    // 功能：兜底捕获其余未预期异常，返回 500 且只给模糊提示｜要点：异常隔离——细节留日志，对外不泄露内部信息
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: ", e);
        return Result.error(500, "系统内部错误，请稍后重试");
    }
}
