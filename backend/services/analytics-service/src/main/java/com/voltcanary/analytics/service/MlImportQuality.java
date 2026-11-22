package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MlImportedMetricRow;
import com.voltcanary.analytics.service.MlExternalImportParser.ImportResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MlImportQuality {
    private MlImportQuality() {}

    static Map<String, Object> qualityProfile(ImportResult result) {
        List<MlImportedMetricRow> rows = result.acceptedRows();
        Map<String, Long> coverageByDeviceMetric = new LinkedHashMap<>();
        for (MlImportedMetricRow row : rows) {
            coverageByDeviceMetric.merge(
                    row.getExternalDeviceKey() + ":" + row.getMetricName(), 1L, Long::sum);
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("rowCount", rows.size());
        profile.put("rejectedRows", result.rejectedRows());
        profile.put(
                "deviceCount",
                rows.stream().map(MlImportedMetricRow::getExternalDeviceKey).distinct().count());
        profile.put(
                "metricCount",
                rows.stream().map(MlImportedMetricRow::getMetricName).distinct().count());
        profile.put("timeRange", importTimeRange(rows));
        profile.put("coverageByDeviceMetric", coverageByDeviceMetric);
        profile.put("maxSamplingGapSeconds", maxSamplingGapSeconds(rows));
        profile.put("duplicateTimestampRate", duplicateTimestampRate(rows));
        profile.put(
                "nonFiniteImpossibleValueCount",
                result.rejectedReasons().getOrDefault("NON_FINITE_METRIC_VALUE", 0L)
                        + impossibleValueCount(rows));
        profile.put(
                "unitNormalizationWarnings",
                result.rejectedReasons().getOrDefault("METRIC_UNIT_MISMATCH", 0L));
        profile.put("maintenanceOverlapCount", 0L);
        profile.put("labelCoverage", Map.of());
        return profile;
    }

    static long maxSamplingGapSeconds(List<MlImportedMetricRow> rows) {
        Map<String, List<MlImportedMetricRow>> groups = new LinkedHashMap<>();
        for (MlImportedMetricRow row : rows) {
            groups.computeIfAbsent(
                            row.getExternalDeviceKey() + ":" + row.getMetricName(),
                            ignored -> new ArrayList<>())
                    .add(row);
        }
        long max = 0;
        for (List<MlImportedMetricRow> group : groups.values()) {
            List<MlImportedMetricRow> sorted =
                    group.stream()
                            .sorted(Comparator.comparing(MlImportedMetricRow::getTimestamp))
                            .toList();
            for (int index = 1; index < sorted.size(); index++) {
                long gap =
                        java.time.Duration.between(
                                        sorted.get(index - 1).getTimestamp(),
                                        sorted.get(index).getTimestamp())
                                .abs()
                                .toSeconds();
                max = Math.max(max, gap);
            }
        }
        return max;
    }

    static double duplicateTimestampRate(List<MlImportedMetricRow> rows) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        Set<String> seen = new HashSet<>();
        long duplicates = 0;
        for (MlImportedMetricRow row : rows) {
            String key =
                    row.getExternalDeviceKey()
                            + "|"
                            + row.getMetricName()
                            + "|"
                            + row.getTimestamp();
            if (!seen.add(key)) {
                duplicates++;
            }
        }
        return (double) duplicates / rows.size();
    }

    static long impossibleValueCount(List<MlImportedMetricRow> rows) {
        return rows.stream()
                .filter(
                        row ->
                                isKnownNonNegativeMetric(row.getMetricName())
                                        && row.getNumericValue() < 0.0)
                .count();
    }

    static boolean isKnownNonNegativeMetric(String metricName) {
        return MlImportSchema.expectedUnit(metricName) != null;
    }

    static Map<String, String> importTimeRange(List<MlImportedMetricRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Instant min =
                rows.stream()
                        .map(MlImportedMetricRow::getTimestamp)
                        .min(Instant::compareTo)
                        .orElseThrow();
        Instant max =
                rows.stream()
                        .map(MlImportedMetricRow::getTimestamp)
                        .max(Instant::compareTo)
                        .orElseThrow();
        return Map.of("from", min.toString(), "to", max.toString());
    }
}
