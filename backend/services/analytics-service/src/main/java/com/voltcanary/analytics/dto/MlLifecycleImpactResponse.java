package com.voltcanary.analytics.dto;

import java.util.List;
import java.util.Map;

public record MlLifecycleImpactResponse(
        String action,
        boolean blocked,
        String message,
        List<Map<String, Object>> dependencies) {
}
