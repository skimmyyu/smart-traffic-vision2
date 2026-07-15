package com.traffic.config;

import com.traffic.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(IllegalArgumentException ex) {
        return Result.of(400, ex.getMessage(), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleIllegalState(IllegalStateException ex) {
        log.warn("业务状态异常: {}", ex.getMessage());
        return Result.of(500, ex.getMessage(), null);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleDuplicate(DuplicateKeyException ex) {
        return Result.of(409, ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleGeneric(Exception ex) {
        log.error("未处理异常", ex);
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = "服务器内部错误";
        } else if (!msg.contains("失败") && !msg.contains("错误") && !msg.contains("异常")) {
            msg = "服务器内部错误: " + msg;
        }
        return Result.of(500, msg, null);
    }
}
