package org.bluesky.training.display;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DisplaySettingsValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public DisplaySettingsValidationException(Map<String, String> fieldErrors) {
        super("显示颜色格式不合法");
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
