package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.Device;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ScopeSnapshot;
import com.voltcanary.dashboard.query.QueryTypes.Config;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Presentation projections retain owner counts and provenance; pages never stand in for totals. */
@Component
public class WidgetEvidenceProjection {
    private final ObjectMapper mapper;
    public WidgetEvidenceProjection(ObjectMapper mapper) { this.mapper = mapper; }
    public List<String> owners(String type) {
        return switch (type) {
            case "fleet-count", "latest-value", "time-series", "metric-summary" -> List.of("telemetry");
            case "energy-period","peak-and-ranking" -> List.of("energy");
            case "quality-and-freshness" -> List.of("telemetry", "analytics");
            case "risk-list", "prediction-evidence" -> List.of("analytics");
            case "incident-list" -> List.of("incidents");
            case "maintenance-agenda" -> List.of("maintenance");
            case "location-matrix" -> List.of("telemetry", "analytics", "incidents");
            default -> List.of();
        };
    }
    public ObjectNode project(Widget widget, Config config, Frame frame, ScopeSnapshot scope, Map<String, JsonNode> evidence) {
        for (JsonNode source : evidence.values()) {
            if (source.path("deniedBindingCount").asLong() > 0) throw new DashboardFailure("NO_ACCESS", "Resource access changed during the read");
            if (source.path("evaluatedCount").asLong(-1) != scope.devices().size())
                throw new DashboardFailure("INVALID_READ_RESPONSE", "Owner evaluated a different scope");
            var expected = scope.devices().stream().map(device -> device.id().toString()).collect(java.util.stream.Collectors.toSet());
            var actual = new java.util.HashSet<String>();
            source.path("authorizedDeviceIds").forEach(id -> actual.add(id.asText()));
            if (!source.path("authorizedDeviceIds").isArray() || actual.size() != source.path("authorizedDeviceIds").size() || !actual.equals(expected))
                throw new DashboardFailure("INVALID_READ_RESPONSE", "Owner response resource IDs differ from the authorized scope");
        }
        String resultType = switch (widget.type()) {
            case "fleet-count" -> "COUNT";
            case "latest-value" -> "VALUE";
            case "time-series" -> "SERIES";
            case "metric-summary" -> "SUMMARY";
            case "incident-list" -> "INCIDENTS";
            case "maintenance-agenda" -> "MAINTENANCE";
            case "risk-list" -> "RISK";
            case "quality-and-freshness" -> "QUALITY";
            case "location-matrix" -> "MATRIX";
            case "prediction-evidence" -> "PREDICTION";
            case "energy-period" -> "ENERGY";
            case "peak-and-ranking" -> "ENERGY_RANKING";
            default -> "NOTE";
        };
        ObjectNode result = mapper.createObjectNode().put("resultType", resultType).put("widgetType", widget.type())
                .put("singleAccount", true).put("scopeOverride", "FIXED".equals(widget.binding() == null ? "" : widget.binding().path("mode").asText()));
        result.set("devices", mapper.valueToTree(scope.devices()));
        result.set("locations", mapper.valueToTree(scope.locations()));
        // Every domain keeps its time basis, query timestamp, count and exclusion metadata.
        ObjectNode sources = result.putObject("sources");
        evidence.forEach((owner, source) -> {
            ObjectNode qualified = source.deepCopy();
            if ("risk-list".equals(widget.type()) || "prediction-evidence".equals(widget.type())) {
                qualified.remove(List.of("risks", "predictions"));
            }
            sources.set(owner, qualified);
        });
        if (List.of("latest-value", "metric-summary", "time-series").contains(widget.type())) registered(config, evidence.get("telemetry"));
        switch (widget.type()) {
            case "fleet-count", "quality-and-freshness" -> freshness(result, config, frame, scope, evidence.get("telemetry"));
            case "latest-value" -> latest(result, config, scope, evidence.get("telemetry"));
            case "time-series" -> series(result, config, evidence.get("telemetry"));
            case "metric-summary" -> summary(result, config, evidence.get("telemetry"));
            case "energy-period","peak-and-ranking" -> energy(result,evidence.get("energy"));
            case "risk-list" -> page(result, "risks", config, taskRisks(widget, scope, evidence.get("analytics")));
            case "prediction-evidence" -> page(result, "predictions", config, evidence.get("analytics"));
            case "location-matrix" -> matrix(result, config, frame, scope, evidence);
            case "context-note" -> note(result, widget);
            default -> { }
        }
        return result;
    }

    private void energy(ObjectNode result,JsonNode source) {
        JsonNode period=source.path("period");
        if (!period.isObject()) throw new DashboardFailure("INVALID_READ_RESPONSE","Energy owner period is missing");
        result.set("energy",period);
        Double current=energyNumber(period,"energyKWh");
        JsonNode previous=source.path("baseline");
        Double baseline=energyNumber(previous,"energyKWh");
        result.set("comparison",mapper.valueToTree(QueryTypes.Comparison.of(current,baseline)));
        result.set("baselineEnergy",previous);
        result.put("peakMeaning","HIGHEST_RECORDED_EQUIPMENT_POWER_NOT_SIMULTANEOUS_FLEET_PEAK");
        result.put("scopeWarning",source.path("scopeWarning").asText());
    }

    private Double energyNumber(JsonNode period,String key) {
        if (!period.hasNonNull(key)) return null;
        JsonNode value=period.path(key);
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble()<0)
            throw new DashboardFailure("INVALID_READ_RESPONSE","Energy evidence must be finite nonnegative recorded values");
        return value.asDouble();
    }
    private void freshness(ObjectNode result, Config config, Frame frame, ScopeSnapshot scope, JsonNode telemetry) {
        ObjectNode counts = result.putObject("counts");
        long fresh = 0, stale = 0, unknown = 0, manual = 0, sparse = 0, active = 0;
        ArrayNode rows = result.putArray("freshness");
        for (Device device : scope.devices()) {
            if ("ACTIVE".equals(device.registrationStatus())) active++;
            Instant measured = null;
            for (JsonNode sample : telemetry.path("latest")) {
                if (device.id().toString().equals(sample.path("deviceId").asText()) && sample.hasNonNull("measuredAt")) {
                    Instant at = Instant.parse(sample.get("measuredAt").asText());
                    if (measured == null || at.isAfter(measured)) measured = at;
                }
            }
            String policy = config.freshnessPolicy() == null ? device.sourcePolicy() : config.freshnessPolicy().name();
            Long cadence = config.cadenceSeconds() == null ? device.cadenceSeconds() : config.cadenceSeconds();
            long threshold = cadence == null ? 300 : Math.max(60, cadence * 3);
            String state;
            if ("MANUAL".equals(policy)) { state = "manual_import"; manual++; }
            else if ("SPARSE".equals(policy)) { state = "sparse"; sparse++; }
            else if (measured == null) { state = "unknown"; unknown++; }
            else if (Duration.between(measured, frame.asOf()).getSeconds() <= threshold) { state = "fresh"; fresh++; }
            else { state = "stale"; stale++; }
            ObjectNode row = rows.addObject().put("deviceId", device.id().toString()).put("state", state)
                    .put("thresholdSeconds", threshold).put("cadenceBasis", cadence == null ? "DASHBOARD_DEFAULT" : "CONFIGURED_SOURCE_OR_WIDGET")
                    .put("meaning", "MEASUREMENT_AGE_NOT_CONNECTIVITY");
            if (measured != null) row.put("measuredAt", measured.toString()); else row.putNull("measuredAt");
        }
        counts.put("registered", scope.devices().size()).put("registrationActive", active).put("fresh", fresh)
                .put("stale", stale).put("unknown", unknown).put("manualImport", manual).put("sparse", sparse);
    }
    private void latest(ObjectNode result, Config config, ScopeSnapshot scope, JsonNode source) {
        if (scope.devices().size() != 1 || config.metric() == null)
            throw new DashboardFailure("VALIDATION_ERROR", "Latest value requires one device and a metric");
        JsonNode found = null;
        for (JsonNode sample : source.path("latest")) if (config.metric().equals(sample.path("metric").asText())) found = sample;
        if (found != null) { unit(config, found.path("unit").asText()); result.set("value", found); }
        else result.putNull("value");
    }
    private void series(ObjectNode result, Config config, JsonNode source) {
        String commonUnit = null;
        for (JsonNode series : source.path("series")) for (JsonNode bucket : series.path("buckets")) {
            String current = bucket.path("unit").asText(); unit(config, current);
            if (commonUnit != null && !commonUnit.equals(current)) throw new DashboardFailure("VALIDATION_ERROR", "Series have incompatible canonical units");
            commonUnit = current;
        }
        result.set("series", source.path("series"));
        result.put("reducer", config.reducer() == null ? "RAW" : config.reducer().name())
                .put("displayMeaning", "OBSERVED_BINS_WITH_EXTREMA_NO_INTERPOLATION");
    }
    private void summary(ObjectNode result, Config config, JsonNode source) {
        if (config.metric() == null) throw new DashboardFailure("VALIDATION_ERROR", "Summary requires a metric");
        JsonNode current = null, baseline = null;
        for (JsonNode row : source.path("summaries")) if (config.metric().equals(row.path("metric").asText())) {
            if (current != null) throw new DashboardFailure("VALIDATION_ERROR", "Mixed units cannot be reduced together");
            unit(config, row.path("unit").asText()); current = row;
        }
        for (JsonNode row : source.path("baselineSummaries")) if (config.metric().equals(row.path("metric").asText())) {
            if (baseline != null) throw new DashboardFailure("VALIDATION_ERROR", "Mixed baseline units"); baseline = row;
        }
        result.set("summary", current == null ? mapper.nullNode() : current);
        result.set("baseline", baseline == null ? mapper.nullNode() : baseline);
        String field = switch (config.reducer() == null ? QueryTypes.Reducer.MEAN : config.reducer()) {
            case MIN -> "minimum"; case MAX -> "maximum"; case P95 -> "p95"; default -> "mean";
        };
        QueryTypes.Comparison comparison = QueryTypes.Comparison.of(number(current, field), number(baseline, field));
        if (current != null && baseline != null && !current.path("unit").equals(baseline.path("unit")))
            comparison = new QueryTypes.Comparison(null, null, "incompatible_units");
        else if (current != null && "C".equals(current.path("unit").asText()))
            comparison = new QueryTypes.Comparison(comparison.difference(), null, "non_ratio_unit");
        result.set("comparison", mapper.valueToTree(comparison));
        result.put("reducer", field).put("coverageMeaning", "DEVICES_WITH_ACCEPTED_SAMPLES_NOT_ALL_REGISTERED_DEVICES");
    }
    private Double number(JsonNode row, String field) { return row != null && row.path(field).isNumber() ? row.get(field).asDouble() : null; }
    private void unit(Config config, String unit) {
        if (config.unit() != null && !config.unit().equals(unit)) throw new DashboardFailure("VALIDATION_ERROR", "Source unit differs from the selected canonical unit");
    }
    private JsonNode taskRisks(Widget widget, ScopeSnapshot scope, JsonNode source) {
        if (widget.presentation() == null || !"HIGH_OR_UNKNOWN".equals(widget.presentation().path("riskFilter").asText())) return source;
        ObjectNode selected = source.deepCopy();
        ArrayNode rows = selected.putArray("risks");
        java.util.Set<String> known = new java.util.HashSet<>();
        source.path("risks").forEach(row -> {
            known.add(row.path("deviceId").asText());
            if (List.of("HIGH", "CRITICAL", "UNKNOWN").contains(row.path("level").asText()) || !row.path("score").isNumber()) rows.add(row);
        });
        scope.devices().stream().filter(device -> !known.contains(device.id().toString())).forEach(device -> rows.addObject()
                .put("deviceId", device.id().toString()).put("level", "UNKNOWN").putNull("score").putNull("measuredAt")
                .put("explanation", "No stored risk record; unknown is not healthy"));
        return selected;
    }
    private void page(ObjectNode result, String key, Config config, JsonNode source) {
        JsonNode records = source.path(key);
        java.util.List<JsonNode> sorted = new java.util.ArrayList<>();
        records.forEach(sorted::add);
        sorted.sort("risks".equals(key)
                ? java.util.Comparator.<JsonNode>comparingDouble(row -> row.path("score").asDouble(Double.NEGATIVE_INFINITY)).reversed()
                    .thenComparing(row -> row.path("deviceId").asText())
                : java.util.Comparator.<JsonNode, String>comparing(row -> row.path("measuredAt").asText()).reversed()
                    .thenComparing(row -> row.path("deviceId").asText()));
        ArrayNode rows = result.putArray("rows");
        for (int i = config.offset(); i < Math.min(records.size(), config.offset() + config.rows()); i++) {
            ObjectNode row = sorted.get(i).deepCopy();
            if ("predictions".equals(key) && (!List.of("TRAINED_MODEL", "BASELINE_HEURISTIC", "READY_ML", "READY_HEURISTIC")
                    .contains(row.path("predictionStatus").asText()) || !row.path("confidence").isNumber()
                    || row.path("confidence").asDouble() < 0 || row.path("confidence").asDouble() > 1)) {
                row.putNull("confidence"); row.put("confidenceState", "UNAVAILABLE_PREDICTION_STATUS");
            }
            rows.add(row);
        }
        result.put("total", records.size()).put("hasNext", config.offset() + rows.size() < records.size())
                .put("meaning", "STORED_EVIDENCE_NOT_NEW_INFERENCE");
    }
    private void matrix(ObjectNode result, Config config, Frame frame, ScopeSnapshot scope, Map<String, JsonNode> evidence) {
        ArrayNode rows = result.putArray("rows");
        for (var location : scope.locations()) {
            var ids = scope.devices().stream().filter(device -> location.id().equals(device.locationId())).map(device -> device.id().toString()).collect(java.util.stream.Collectors.toSet());
            ObjectNode row = rows.addObject().put("locationId", location.id().toString()).put("name", location.name()).put("registered", ids.size());
            matrixRow(row, ids, config, frame, scope, evidence);
        }
        var unassigned = scope.devices().stream().filter(device -> device.locationId() == null).map(device -> device.id().toString()).collect(java.util.stream.Collectors.toSet());
        if (!unassigned.isEmpty()) {
            ObjectNode row = rows.addObject().putNull("locationId").put("name", "Unassigned").put("registered", unassigned.size());
            matrixRow(row, unassigned, config, frame, scope, evidence);
        }
        result.putObject("columnTimeBasis").put("registered", "SNAPSHOT").put("freshness", "MEASUREMENT_AGE_AT_ASOF")
                .put("risk", "LATEST_STORED_RECORD_AT_ASOF").put("activeIncidents", "CURRENT_STATUS_AT_OWNER_READ")
                .put("energy", "UNSUPPORTED_DEPENDENCY");
        result.put("total", rows.size());
        ArrayNode visible = mapper.createArrayNode();
        for (int i = config.offset(); i < Math.min(rows.size(), config.offset() + config.rows()); i++) visible.add(rows.get(i));
        result.set("rows", visible); result.put("hasNext", config.offset() + visible.size() < rows.size());
    }
    private void matrixRow(ObjectNode row, java.util.Set<String> ids, Config config, Frame frame, ScopeSnapshot scope, Map<String, JsonNode> evidence) {
            long active = 0, critical = 0, riskKnown = 0;
            double riskSum = 0;
            for (JsonNode incident : evidence.get("incidents").path("byDevice")) if (ids.contains(incident.path("deviceId").asText())) {
                active += incident.path("activeNow").asLong(); critical += incident.path("criticalOpenNow").asLong();
            }
            for (JsonNode risk : evidence.get("analytics").path("risks")) if (ids.contains(risk.path("deviceId").asText()) && risk.path("score").isNumber()) {
                riskKnown++; riskSum += risk.path("score").asDouble();
            }
            row.put("activeIncidents", active).put("criticalIncidents", critical).put("riskEvidenceDevices", riskKnown)
                    .put("timeBasis", "CURRENT_REGISTRATION_AND_STORED_RECORDS_AT_ASOF");
            row.putNull("energy"); row.put("energyState", "UNSUPPORTED_DEPENDENCY");
            if (riskKnown == 0 || !Double.isFinite(riskSum)) row.putNull("riskMean"); else row.put("riskMean", riskSum / riskKnown);
            java.util.Set<String> measured = new java.util.HashSet<>();
            for (JsonNode sample : evidence.get("telemetry").path("latest")) if (ids.contains(sample.path("deviceId").asText())) measured.add(sample.path("deviceId").asText());
            row.put("measurementEvidenceDevices", measured.size()).put("measurementUnknownDevices", ids.size() - measured.size());
            var subset = scope.devices().stream().filter(device -> ids.contains(device.id().toString())).toList();
            ObjectNode age = mapper.createObjectNode();
            freshness(age, config, frame, new ScopeSnapshot(scope.userId(), scope.accountId(), subset.size(), subset.size(),
                    0, subset, List.of(), false, scope.resolvedAt()), evidence.get("telemetry"));
            row.set("freshness", age.path("counts"));
    }
    private void note(ObjectNode result, Widget widget) {
        String text = widget.payload() == null ? "" : widget.payload().path("text").asText();
        if (text.length() > 1000) throw new DashboardFailure("LIMIT_EXCEEDED", "Notes are limited to 1000 characters");
        result.put("text", text).put("format", "PLAIN_TEXT");
        result.set("navigation", widget.navigation() == null ? mapper.nullNode() : widget.navigation());
    }
    public void registered(Config config, JsonNode source) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        source.path("registeredMetrics").forEach(metric -> keys.add(metric.path("key").asText()));
        if (config.metric() != null && !keys.contains(config.metric())
                || config.series().stream().anyMatch(series -> !keys.contains(series.metric()))) {
            throw new DashboardFailure("VALIDATION_ERROR", "Metric key is not registered by the telemetry owner");
        }
        if (config.unit() != null) for (JsonNode metric : source.path("registeredMetrics")) {
            if ((metric.path("key").asText().equals(config.metric()) || config.series().stream().anyMatch(series -> series.metric().equals(metric.path("key").asText())))
                    && !config.unit().equals(metric.path("unit").asText()))
                throw new DashboardFailure("VALIDATION_ERROR", "Selected unit differs from the registered canonical unit");
        }
    }
}
