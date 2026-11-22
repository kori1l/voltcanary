package com.voltcanary.device.service;

import com.voltcanary.device.domain.MaintenanceOperation;
import com.voltcanary.device.domain.MaintenanceWindow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

final class MaintenanceValues {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private MaintenanceValues() {}

    static String status(MaintenanceOperation operation, Instant now) {
        if (operation.getCancelledAt() != null) {
            return "CANCELLED";
        }
        if (now.isBefore(operation.getStartAt())) {
            return "SCHEDULED";
        }
        if (now.isBefore(operation.getEndAt())) {
            return "ACTIVE";
        }
        return "FINISHED";
    }

    static String status(MaintenanceWindow window, Instant now) {
        if (window.getCancelledAt() != null) {
            return "CANCELLED";
        }
        if (now.isBefore(window.getStartAt())) {
            return "SCHEDULED";
        }
        if (now.isBefore(window.getEndAt())) {
            return "ACTIVE";
        }
        return "FINISHED";
    }

    static boolean isFinished(MaintenanceOperation operation, Instant now) {
        return operation.getCancelledAt() == null && !now.isBefore(operation.getEndAt());
    }

    static boolean isFinished(MaintenanceWindow window, Instant now) {
        return window.getCancelledAt() == null && !now.isBefore(window.getEndAt());
    }

    static void validateRange(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null) {
            throw new IllegalArgumentException("startAt and endAt are required");
        }
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
    }

    static void validateQueryRange(Instant from, Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from");
        }
    }

    static String normalizeRequired(String value, int maxLength, String field) {
        String normalized = normalizeOptional(value, maxLength, field);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    static String normalizeOptional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                field + " must be at most " + maxLength + " characters"
            );
        }
        return normalized;
    }

    static int normalizedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    static int normalizedOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        return offset;
    }

    static boolean defaultTrue(Boolean value) {
        return value == null || value;
    }

    static List<UUID> normalizeDeviceIds(List<UUID> deviceIds) {
        if (deviceIds == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(deviceIds));
    }
}
