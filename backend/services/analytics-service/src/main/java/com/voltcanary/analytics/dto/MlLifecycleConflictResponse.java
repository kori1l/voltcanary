package com.voltcanary.analytics.dto;

import java.util.List;
import java.util.Map;

public record MlLifecycleConflictResponse(
        String code,
        String message,
        List<Map<String, Object>> dependencies) {
}
