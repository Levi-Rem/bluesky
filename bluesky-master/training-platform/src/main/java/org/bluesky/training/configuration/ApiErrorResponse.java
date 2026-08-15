package org.bluesky.training.configuration;

import java.util.Collections;
import java.util.List;

public final class ApiErrorResponse {
    private final String code;
    private final String message;
    private final List<FieldErrorView> fieldErrors;
    private final String requestId;

    public ApiErrorResponse(String code, String message, List<FieldErrorView> fieldErrors,
                            String requestId) {
        this.code = code;
        this.message = message;
        this.fieldErrors = fieldErrors;
        this.requestId = requestId;
    }

    public static ApiErrorResponse fieldError(String field, String message, String requestId) {
        return new ApiErrorResponse(
                "VALIDATION_FAILED",
                message,
                Collections.singletonList(new FieldErrorView(field, message)),
                requestId);
    }

    public static ApiErrorResponse engineUnavailable(String message, String requestId) {
        return new ApiErrorResponse(
                "ENGINE_UNAVAILABLE", message, Collections.emptyList(), requestId);
    }

    public static ApiErrorResponse engineRejected(String code, String message, String requestId) {
        return new ApiErrorResponse(code, message, Collections.emptyList(), requestId);
    }

    public static ApiErrorResponse invalidState(String message, String requestId) {
        return new ApiErrorResponse(
                "INVALID_STATE", message, Collections.emptyList(), requestId);
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public List<FieldErrorView> getFieldErrors() { return fieldErrors; }
    public String getRequestId() { return requestId; }

    public static final class FieldErrorView {
        private final String field;
        private final String message;

        public FieldErrorView(String field, String message) {
            this.field = field;
            this.message = message;
        }

        public String getField() { return field; }
        public String getMessage() { return message; }
    }
}
