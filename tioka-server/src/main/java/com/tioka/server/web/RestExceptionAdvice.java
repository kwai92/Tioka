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
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getErrorCode().getHttpStatus());
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(ex.getErrorCode(), ex.getMessage(), ex.getData()));
    }
}
