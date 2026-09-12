package org.bluesky.training.common;

import java.util.Collections;
import java.util.List;

/** 第二版领域失败：错误码/HTTP 状态/字段来自详细设计 12.1 的固定集合。 */
public class V2DomainException extends RuntimeException {

    private final String code;
    private final int httpStatus;
    private final List<String> fields;
    private final List<String> warnings;

    public V2DomainException(String code, int httpStatus, String message) {
        this(code, httpStatus, message, Collections.emptyList(), Collections.emptyList());
    }

    public V2DomainException(String code, int httpStatus, String message, List<String> fields) {
        this(code, httpStatus, message, fields, Collections.emptyList());
    }

    public V2DomainException(String code, int httpStatus, String message,
                             List<String> fields, List<String> warnings) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.fields = Collections.unmodifiableList(fields);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public List<String> fields() {
        return fields;
    }

    public List<String> warnings() {
        return warnings;
    }
}
