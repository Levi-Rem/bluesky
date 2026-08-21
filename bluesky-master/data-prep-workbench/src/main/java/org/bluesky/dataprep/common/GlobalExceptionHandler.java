package org.bluesky.dataprep.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getStatus(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(body(400, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        return ResponseEntity.status(500).body(body(500, "服务内部错误：" + ex.getMessage()));
    }

    private Map<String, Object> body(int code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        return map;
    }
}
