package com.voltcanary.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.voltcanary.dashboard.domain.DashboardDefinition.Widget;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.query.QueryTimeResolver;
import com.voltcanary.dashboard.query.QueryTypes;
import com.voltcanary.dashboard.query.WidgetEvidenceProjection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetEvidenceProjectionTest {
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final WidgetEvidenceProjection projection = new WidgetEvidenceProjection(mapper);
    private final UUID device = UUID.randomUUID();
    private final Instant asOf = Instant.parse("2026-10-09T00:00:00Z");
    private final DashboardEvidenceReads.ScopeSnapshot scope = new DashboardEvidenceReads.ScopeSnapshot(UUID.randomUUID(), UUID.randomUUID(), 1, 1, 0,
            List.of(new DashboardEvidenceReads.Device(device, "Fixture", "SENSOR", null, "ACTIVE", null, "DASHBOARD_DEFAULT", "MANUAL", asOf)), List.of(), false, asOf);
    private final QueryTypes.Frame frame = new QueryTimeResolver().resolve(QueryTypes.Time.defaultTime(), null, null, asOf);
    private final QueryTypes.Config config = new QueryTypes.Config("activePowerW", "W", QueryTypes.Reducer.MEAN, null, null, 20, 0, null, null, null, List.of(), List.of());
    private Widget widget(String type) { return new Widget("fixture", type, 1, "Fixture", null, null, null, null, null); }
    private com.fasterxml.jackson.databind.node.ObjectNode source() {
        var result = mapper.createObjectNode().put("evaluatedCount", 1).put("deniedBindingCount", 0);
        result.putArray("authorizedDeviceIds").add(device.toString());
        result.putArray("registeredMetrics").addObject().put("key", "activePowerW").put("unit", "W");
        return result;
    }
    @Test void energyKeepsCoverageAndUndefinedZeroBaselinePercentage() {
        var energy=source();
        energy.putObject("period").put("energyKWh",0.5).put("coverage","PARTIAL").put("coverageFraction",0.5).put("estimate",false);
        energy.putObject("baseline").put("energyKWh",0).put("coverage","COMPLETE").put("coverageFraction",1);
        var result=projection.project(widget("energy-period"),config,frame,scope,Map.of("energy",energy));
        assertThat(result.path("energy").path("coverage").asText()).isEqualTo("PARTIAL");
        assertThat(result.path("comparison").path("difference").asDouble()).isEqualTo(0.5);
        assertThat(result.path("comparison").path("percent").isNull()).isTrue();
        assertThat(result.path("comparison").path("reason").asText()).isEqualTo("zero_baseline");
        assertThat(result.path("peakMeaning").asText()).contains("NOT_SIMULTANEOUS_FLEET_PEAK");
    }
    @Test void taskRiskShowsHighAndMissingRecordsWithoutInventingScores() {
        var riskWidget = new Widget("risk", "risk-list", 1, "Risk", null, null,
                mapper.createObjectNode().put("riskFilter", "HIGH_OR_UNKNOWN"), null, null);
        var source = source();source.putArray("risks");
        var unknown = projection.project(riskWidget, config, frame, scope, Map.of("analytics", source));
        assertThat(unknown.path("rows").get(0).path("level").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.path("rows").get(0).path("score").isNull()).isTrue();
        source.withArray("risks").addObject().put("deviceId", device.toString()).put("level", "LOW").put("score", 0.1);
        assertThat(projection.project(riskWidget, config, frame, scope, Map.of("analytics", source)).path("total").asInt()).isZero();
        ((com.fasterxml.jackson.databind.node.ObjectNode)source.path("risks").get(0)).put("level", "HIGH").put("score", 0.8);
        assertThat(projection.project(riskWidget, config, frame, scope, Map.of("analytics", source)).path("total").asInt()).isEqualTo(1);
    }
    @Test void zeroSummaryBaselineAndManualAgeStayExplicit() {
        var source = source();
        source.putArray("summaries").addObject().put("metric", "activePowerW").put("unit", "W").put("mean", 0).put("sampleCount", 500).put("deviceCount", 1);
        source.putArray("baselineSummaries").addObject().put("metric", "activePowerW").put("unit", "W").put("mean", 0);
        var summary = projection.project(widget("metric-summary"), config, frame, scope, Map.of("telemetry", source));
        assertThat(summary.path("resultType").asText()).isEqualTo("SUMMARY");
        assertThat(summary.path("comparison").path("difference").asDouble()).isZero();
        assertThat(summary.path("comparison").path("percent").isNull()).isTrue();
        assertThat(summary.path("comparison").path("reason").asText()).isEqualTo("zero_baseline");
        var fleet = projection.project(widget("fleet-count"), config, frame, scope, Map.of("telemetry", source));
        assertThat(fleet.path("counts").path("manualImport").asInt()).isEqualTo(1);
        assertThat(fleet.path("counts").path("fresh").asInt()).isZero();
    }
    @Test void recordedTrainedConfidenceIsNotAccuracyAndUnavailablePredictionHasNone() {
        var source = source();
        source.putArray("predictions").addObject().put("deviceId", device.toString()).put("predictionStatus", "TRAINED_MODEL").put("confidence", 0.8);
        var result = projection.project(widget("prediction-evidence"), config, frame, scope, Map.of("analytics", source));
        assertThat(result.path("rows").get(0).path("confidence").asDouble()).isEqualTo(0.8);
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.path("predictions").get(0)).put("predictionStatus", "UNAVAILABLE");
        result = projection.project(widget("prediction-evidence"), config, frame, scope, Map.of("analytics", source));
        assertThat(result.path("rows").get(0).path("confidence").isNull()).isTrue();
    }
    @Test void forgedOwnerIdsAndWrongCanonicalUnitCannotBecomeData() {
        var source = source();
        source.putArray("authorizedDeviceIds").add(UUID.randomUUID().toString());
        var forged = source;
        assertThatThrownBy(() -> projection.project(widget("fleet-count"), config, frame, scope, Map.of("telemetry", forged)))
                .isInstanceOf(DashboardFailure.class);
        source = source();
        source.withArray("registeredMetrics").removeAll().addObject().put("key", "activePowerW").put("unit", "V");
        var wrongUnit = source;
        assertThatThrownBy(() -> projection.registered(config, wrongUnit)).isInstanceOf(DashboardFailure.class);
    }
}
