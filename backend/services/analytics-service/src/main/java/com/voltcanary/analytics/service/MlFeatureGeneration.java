package com.voltcanary.analytics.service;

import java.util.List;
import java.util.Map;

public record MlFeatureGeneration(
        long rowCount,
        long rejectedRowCount,
        Map<String, Object> schema,
        List<MlFeatureRowData> rows) {
}
