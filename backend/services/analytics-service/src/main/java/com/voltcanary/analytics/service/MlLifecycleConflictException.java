package com.voltcanary.analytics.service;

import com.voltcanary.analytics.dto.MlLifecycleConflictResponse;
import java.util.List;
import java.util.Map;

public class MlLifecycleConflictException extends RuntimeException {
    private final MlLifecycleConflictResponse response;

    public MlLifecycleConflictException(String code, String message, List<Map<String, Object>> dependencies) {
        super(message);
        this.response = new MlLifecycleConflictResponse(code, message, dependencies);
    }

    public MlLifecycleConflictException(String code, String message, Map<String, Object> dependency) {
        this(code, message, List.of(dependency));
    }

    public MlLifecycleConflictResponse response() {
        return response;
    }
}
