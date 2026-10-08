package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.query.QueryTypes.Config;
import com.voltcanary.dashboard.query.QueryTypes.Manifest;
import com.voltcanary.dashboard.query.QueryTypes.RuntimeContext;
import com.voltcanary.dashboard.query.QueryTypes.Scope;
import com.voltcanary.dashboard.query.QueryTypes.ScopeMode;
import com.voltcanary.dashboard.query.QueryTypes.Time;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WidgetQueryDecoder {
    private final ObjectMapper mapper;

    public WidgetQueryDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Scope scope(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return Scope.all();
        }
        Scope parsed = decode(value, Scope.class, "scope");
        if (parsed.deviceIds() != null && parsed.deviceIds().contains(null)
                || parsed.locationIds() != null && parsed.locationIds().contains(null)
                || parsed.deviceTypes() != null && parsed.deviceTypes().stream().anyMatch(item -> item == null || !item.matches("[A-Za-z][A-Za-z0-9_]{0,99}"))
                || parsed.locationTags() != null && parsed.locationTags().stream().anyMatch(item -> item == null || item.isBlank() || item.length() > 100)) {
            throw failure("VALIDATION_ERROR", "Scope contains an invalid resource or metadata key", "scope");
        }
        Scope normalized = new Scope(parsed.mode() == null ? ScopeMode.DYNAMIC : parsed.mode(),
                parsed.deviceIds() == null ? java.util.Set.of() : new java.util.TreeSet<>(parsed.deviceIds()),
                parsed.locationIds() == null ? java.util.Set.of() : new java.util.TreeSet<>(parsed.locationIds()), parsed.includeSubtree(),
                parsed.deviceTypes() == null ? java.util.Set.of() : new java.util.TreeSet<>(parsed.deviceTypes()),
                parsed.locationTags() == null ? java.util.Set.of() : new java.util.TreeSet<>(parsed.locationTags()));
        if (normalized.deviceIds().size() > 500 || normalized.locationIds().size() > 500
                || normalized.deviceTypes().size() > 50 || normalized.locationTags().size() > 50
                || (normalized.mode() == ScopeMode.FIXED && normalized.deviceIds().isEmpty() && normalized.locationIds().isEmpty())) {
            throw failure("LIMIT_EXCEEDED", "Invalid or oversized resource binding", "scope");
        }
        return normalized;
    }

    public RuntimeContext runtime(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return new RuntimeContext(Scope.all(), Time.defaultTime());
        }
        RuntimeContext parsed = decode(value, RuntimeContext.class, "runtimeContext");
        return new RuntimeContext(scope(value.get("scope")), parsed.time() == null ? Time.defaultTime() : parsed.time());
    }

    public Config config(Widget widget, Manifest manifest) {
        Config parsed = widget.query() == null || widget.query().isNull()
                ? new Config(null, null, null, null, null, 20, 0, null, null, null, List.of(), List.of())
                : decode(widget.query(), Config.class, "query");
        List<QueryTypes.Series> series = parsed.series() == null ? List.of() : parsed.series();
        int rows = parsed.rows() == 0 ? 20 : parsed.rows();
        if (rows < 1 || rows > manifest.maxRows() || parsed.offset() < 0 || parsed.offset() > 1000000
                || series.size() > manifest.maxSeries()
                || (parsed.cadenceSeconds() != null && (parsed.cadenceSeconds() < 1 || parsed.cadenceSeconds() > 86400))) {
            throw failure("LIMIT_EXCEEDED", "Widget exceeds row/series/cadence bounds", "query");
        }
        if (parsed.reducer() != null && !manifest.reducers().contains(parsed.reducer())) {
            throw failure("VALIDATION_ERROR", "Reducer is unsupported by this widget", "reducer");
        }
        if (parsed.unit() != null && !manifest.units().contains(parsed.unit())) {
            throw failure("VALIDATION_ERROR", "Unit is unsupported by this widget", "unit");
        }
        if (parsed.metric() != null && !parsed.metric().matches("[A-Za-z][A-Za-z0-9_]{0,99}")) {
            throw failure("VALIDATION_ERROR", "Metric must be a registered key", "metric");
        }
        if (series.stream().anyMatch(item -> item == null || item.deviceId() == null || item.metric() == null
                || !item.metric().matches("[A-Za-z][A-Za-z0-9_]{0,99}"))) {
            throw failure("VALIDATION_ERROR", "Series require an owned device and a metric key", "series");
        }
        if (parsed.columns() != null && (parsed.columns().size() > 8 || parsed.columns().stream().anyMatch(column ->
                !List.of("registered", "freshness", "risk", "activeIncidents", "criticalIncidents", "energy").contains(column)))) {
            throw failure("VALIDATION_ERROR", "Unsupported matrix columns", "columns");
        }
        return new Config(parsed.metric(), parsed.unit(), parsed.reducer(), parsed.time(), parsed.compare(), rows,
                parsed.offset(), parsed.freshnessPolicy(), parsed.cadenceSeconds(), parsed.incidentMode(), series,
                parsed.columns() == null ? List.of() : parsed.columns());
    }

    private <T> T decode(JsonNode value, Class<T> type, String field) {
        try {
            return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(value);
        } catch (Exception exception) {
            throw failure("VALIDATION_ERROR", "Unsupported typed fields or values", field);
        }
    }

    private DashboardFailure failure(String code, String message, String field) {
        return new DashboardFailure(code, message, field);
    }
}
