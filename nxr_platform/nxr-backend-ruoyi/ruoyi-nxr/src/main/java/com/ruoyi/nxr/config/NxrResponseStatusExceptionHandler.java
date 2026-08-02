package com.ruoyi.nxr.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * NXR 业务异常处理。
 *
 * 业务代码沿用原工程的 ResponseStatusException（400/404/409 等）。
 * 若依 GlobalExceptionHandler 会把 RuntimeException 统一转成 HTTP 200 + code 500，
 * 但公开站前端依赖真实 HTTP 状态码（如 /api/public/cards/{certId} 的 404），
 * 因此这里更精确地拦截 ResponseStatusException，保留原状态码与错误格式。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class NxrResponseStatusExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException exception) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", exception.getStatusCode().value());
        body.put("message", exception.getReason() != null ? exception.getReason() : exception.getMessage());
        return ResponseEntity.status(exception.getStatusCode()).body(body);
    }
}
