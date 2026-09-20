package com.recordaudit.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码
 */
@Getter
public class BizException extends RuntimeException {

    private final HttpStatus status;

    public BizException(String message) {
        this(message, HttpStatus.BAD_REQUEST);
    }

    public BizException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }
}