package com.voltcanary.analytics.dto;

public record MlLifecycleActionRequest(
        String reason,
        Boolean force) {
}
