package org.bluesky.training.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P01：稳定错误信封（详细设计 9.1：code/message/fields/warnings/requestId）；只作用于 v2 控制器。 */
@RestControllerAdvice(annotations = V2Api.class)
public class V2ApiExceptionHandler {

    @ExceptionHandler(V2DomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomainException(V2DomainException failure,
                                                                     HttpServletRequest request) {
        return envelope(failure.httpStatus(), failure.code(), failure.getMessage(),
                failure.fields(), failure.warnings(), requestId(request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleValidationException(Exception failure,
                                                                         HttpServletRequest request) {
        List<String> fields = new ArrayList<>();
        if (failure instanceof MethodArgumentNotValidException) {
            ((MethodArgumentNotValidException) failure).getBindingResult()
                    .getFieldErrors()
                    .forEach(error -> fields.add(error.getField()));
        }
        return envelope(HttpStatus.BAD_REQUEST.value(), "INVALID_INSTRUCTION",
                "请求参数非法", fields, null, requestId(request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpectedException(Exception failure,
                                                                         HttpServletRequest request) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                "服务器内部错误", null, null, requestId(request));
    }

    public static String requestId(HttpServletRequest request) {
        if (request != null) {
            Object attributed = request.getAttribute("requestId");
            if (attributed != null && !String.valueOf(attributed).trim().isEmpty()) {
                return String.valueOf(attributed);
            }
            String header = request.getHeader("X-Request-Id");
            if (header != null && !header.trim().isEmpty()) {
                return header.trim();
            }
        }
        return "req-" + UUID.randomUUID();
    }

    static ResponseEntity<Map<String, Object>> envelope(int status, String code, String message,
                                                        List<String> fields, List<String> warnings,
                                                        String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        body.put("fields", fields == null ? new ArrayList<String>() : fields);
        body.put("warnings", warnings == null ? new ArrayList<String>() : warnings);
        body.put("requestId", requestId);
        return ResponseEntity.status(status).body(body);
    }
}
