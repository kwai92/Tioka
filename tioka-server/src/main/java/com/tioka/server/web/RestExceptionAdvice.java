package com.tioka.server.web;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestExceptionAdvice {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case DEPENDENCY -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getErrorCode(), ex.getMessage()));
    }
}
