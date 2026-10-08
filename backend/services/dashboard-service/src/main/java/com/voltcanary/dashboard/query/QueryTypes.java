package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class QueryTypes {
    private QueryTypes() {
    }

    public enum ScopeMode { ALL, DYNAMIC, FIXED, UNBOUND }
    public enum TimeMode { INHERITED, RELATIVE, CALENDAR, FIXED }
    public enum Preset { LAST_24_HOURS, LAST_7_DAYS, LAST_30_DAYS, TODAY, WEEK_TO_DATE, MONTH_TO_DATE, NEXT_7_DAYS, NEXT_30_DAYS }
    public enum CompareMode { NONE, PREVIOUS, MANUAL }
    public enum Reducer { RAW, AVG, MIN, MAX, MEAN, P95 }
    public enum FreshnessPolicy { PERIODIC, SPARSE, MANUAL }
    public enum IncidentMode { ACTIVE, CREATED }

    public record Scope(ScopeMode mode, Set<UUID> deviceIds, Set<UUID> locationIds,
            boolean includeSubtree, Set<String> deviceTypes, Set<String> locationTags) {
        public static Scope all() {
            return new Scope(ScopeMode.ALL, Set.of(), Set.of(), false, Set.of(), Set.of());
        }
    }

    public record Time(TimeMode mode, Preset preset, String timezone, Instant from, Instant to) {
        public static Time defaultTime() {
            return new Time(TimeMode.RELATIVE, Preset.LAST_24_HOURS, "UTC", null, null);
        }
    }

    public record Compare(CompareMode mode, Instant from, Instant to) {
    }

    public record RuntimeContext(Scope scope, Time time) {
    }

    public record Config(String metric, String unit, Reducer reducer, Time time,
            Compare compare, int rows, int offset, FreshnessPolicy freshnessPolicy,
            Long cadenceSeconds, IncidentMode incidentMode, List<Series> series, List<String> columns) {
    }

    public record Series(UUID deviceId, String metric) {
    }

    public record Frame(Instant from, Instant to, Instant asOf, String timezone, boolean override,
            Instant compareFrom, Instant compareTo, String comparisonBasis) {
    }

    public record Comparison(Double difference, Double percent, String reason) {
        public static Comparison of(Double current, Double baseline) {
            if (current == null || baseline == null || !Double.isFinite(current) || !Double.isFinite(baseline)) {
                return new Comparison(null, null, "unavailable_evidence");
            }
            double difference = current - baseline;
            if (!Double.isFinite(difference)) {
                return new Comparison(null, null, "numeric_overflow");
            }
            Double percent = baseline == 0 ? null : difference / Math.abs(baseline) * 100;
            if (percent != null && !Double.isFinite(percent)) {
                return new Comparison(difference, null, "numeric_overflow");
            }
            return new Comparison(difference, percent, baseline == 0 ? "zero_baseline" : null);
        }
    }

    public record Manifest(String type, int typeVersion, String title, String resultType,
            boolean supported, String dependency, List<Reducer> reducers, List<String> units,
            int maxSeries, int maxDetailDevices, int maxRows, String timeBasis) {
    }

    public record Panel(String widgetId, String status, JsonNode data, String contextHash, Frame time,
            int requestedCount, int evaluatedCount, Instant receivedAt, Instant measuredAt,
            JsonNode provenance, String errorCode, String explanation) {
    }

    public record Batch(UUID dashboardId, int revision, UUID draftEtag, String contextHash,
            Instant asOf, Instant returnedAt, boolean cached, List<Panel> panels) {
    }
}
