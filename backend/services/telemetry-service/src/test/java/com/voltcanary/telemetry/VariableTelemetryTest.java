package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.common.events.telemetry.ProcessingContext;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.common.events.telemetry.VariableExpression;
import com.voltcanary.telemetry.adapter.VariableSourceAccess;
import com.voltcanary.telemetry.variable.ConfiguredTelemetryHandler;
import com.voltcanary.telemetry.variable.PendingVariableEvents;
import com.voltcanary.telemetry.variable.ProcessingBundles;
import com.voltcanary.telemetry.variable.ProcessingReconciliation;
import com.voltcanary.telemetry.variable.VariableSeriesQueries;
import com.voltcanary.telemetry.variable.domain.VariableEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"app.kafka.consume-enabled=false", "app.kafka.publish-enabled=false",
        "app.telemetry.authorization.enabled=false", "app.variables.enabled=true",
        "app.telemetry.validation.redis-rate-limit-enabled=false", "app.telemetry.reconciliation.enabled=false",
        "management.health.redis.enabled=false"})
class VariableTelemetryTest {
    @Autowired ConfiguredTelemetryHandler handler;
    @Autowired ProcessingBundles bundles;
    @Autowired VariableSeriesQueries queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.voltcanary.telemetry.service.TelemetryValidatedReconciliationService validatedReconciliation;
    @MockitoBean ProcessingReconciliation reconciliation;
    @MockitoBean PendingVariableEvents retries;
    @MockitoBean VariableSourceAccess sourceAccess;

    @Test
    void storesConversionBesideObservedInputsAndPublishesOnce() {
        var bundle = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        bundles.apply(bundle);
        var event = event(bundle, Instant.now().minusSeconds(2), false);
        assertThat(handler.process(event)).isEqualTo(2);
        assertThat(handler.process(event)).isZero();
        var variable = bundle.bindings().getFirst().contract().variableId();
        var latest = queries.latest(bundle.deviceId(), bundle.dataSourceId(), variable);
        assertThat(latest.points()).hasSize(1);
        assertThat(latest.points().getFirst().value()).isEqualTo(22.7);
        assertThat(jdbc.queryForObject("select count(*) from variable_measurement_lineage where source_event_id=?",
                Integer.class, event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select input_snapshot_json from variable_measurement_lineage where source_event_id=?",
                String.class, event.eventId())).contains("235");
        assertThat(jdbc.queryForObject("select count(*) from telemetry_event_outbox where event_key=?",
                Integer.class, "variables:" + event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from telemetry_event_outbox where topic='telemetry.validated.v1'",
                Integer.class)).isZero();
    }

    @Test
    void parksUnknownBundleAndPreservesPinnedRevisionAfterEdit() {
        var first = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        var second = revised(first, 2, 1.8);
        var delayed = event(first, Instant.now().minusSeconds(20), false);
        assertThat(handler.process(delayed)).isZero();
        assertThat(jdbc.queryForObject("select diagnostic from variable_pending_events where event_id=?",
                String.class, delayed.eventId())).isEqualTo("BUNDLE_UNAVAILABLE");
        bundles.apply(second);
        bundles.apply(first);
        assertThat(handler.process(delayed)).isEqualTo(2);
        var newest = event(second, Instant.now().minusSeconds(1), false);
        assertThat(handler.process(newest)).isEqualTo(2);
        var id = first.bindings().getFirst().contract().variableId();
        var series = queries.read(first.deviceId(), first.dataSourceId(), id,
                Instant.now().minusSeconds(60), Instant.now(), 100, null, 60, "AVG");
        assertThat(series.points()).hasSize(2);
        assertThat(series.points()).extracting(VariableSeriesQueries.Point::revision).containsExactly(1, 2);
        assertThat(queries.latest(first.deviceId(), first.dataSourceId(), id).points().getFirst().value()).isEqualTo(21.7);
        assertThat(jdbc.queryForObject("select status from variable_pending_events where event_id=?",
                String.class, delayed.eventId())).isEqualTo("PROCESSED");
    }

    @Test
    void calculationFailureDoesNotDiscardObservedValueOrInventZero() {
        var original = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        var contract = original.bindings().getFirst().contract();
        var invalid = new VariableContract(contract.variableId(), 1, contract.key(), contract.label(), "DERIVED", "C", "v1",
                contract.inputs(), new VariableExpression("DIVIDE", null, null,
                        VariableExpression.input("x"), VariableExpression.constant(0)), null, null);
        var snapshot = new SourceProcessingConfigEvent(original.eventId(), "v1", original.accountId(), original.deviceId(),
                original.dataSourceId(), original.sourceId(), 1, original.digest(), true,
                List.of(new SourceProcessingConfigEvent.Binding(invalid, Map.of("x", "sensorTemperatureRaw"))),
                Instant.now(), "device-service");
        bundles.apply(snapshot);
        var event = event(snapshot, Instant.now().minusSeconds(2), false);
        assertThat(handler.process(event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from variable_measurement_lineage where source_event_id=?",
                String.class, event.eventId())).isEqualTo("DIVISION_BY_ZERO");
        assertThat(queries.latest(snapshot.deviceId(), snapshot.dataSourceId(), contract.variableId()).points()).isEmpty();
    }

    @Test
    void backfillHasExplicitEligibilityAndNoLegacyLiveEvent() {
        var snapshot = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        bundles.apply(snapshot);
        var event = event(snapshot, Instant.now().minusSeconds(2), true);
        handler.process(event);
        assertThat(jdbc.queryForObject("select eligibility from variable_measurement_lineage where source_event_id=?",
                String.class, event.eventId())).isEqualTo("BACKFILL");
        assertThat(jdbc.queryForObject("select payload_json from telemetry_event_outbox where event_key=?",
                String.class, "variables:" + event.eventId())).contains("BACKFILL");
    }

    @Test
    void absentConfiguredInputsRecordFailureWithoutInventingMeasurements() {
        var snapshot = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        bundles.apply(snapshot);
        var template = event(snapshot, Instant.now().minusSeconds(2), false);
        var missing = new TelemetryReceivedEvent(template.eventId(), template.deviceId(), template.sourceId(),
                template.timestamp(), Map.of("configuredFields", Map.of()), template.receivedAt(),
                template.eventVersion(), template.correlationId(), template.producer(), template.accountId(),
                template.occurredAt(), template.processingContext());
        assertThat(handler.process(missing)).isZero();
        assertThat(handler.process(missing)).isZero();
        assertThat(jdbc.queryForObject("select status from variable_measurement_lineage where source_event_id=?",
                String.class, missing.eventId())).isEqualTo("MISSING_INPUT");
        assertThat(queries.latest(snapshot.deviceId(), snapshot.dataSourceId(),
                snapshot.bindings().getFirst().contract().variableId()).points()).isEmpty();
    }

    @Test
    void legacyReconciliationDoesNotPublishVariableOutputsOrFreshImports() {
        var snapshot = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        bundles.apply(snapshot);
        var live = event(snapshot, Instant.now().minusSeconds(3), false);
        handler.process(live);
        var template = event(snapshot, Instant.now().minusSeconds(2), true);
        var metrics = new java.util.LinkedHashMap<String, Object>(template.metrics());
        metrics.put("temperatureC", 23.0);
        var imported = new TelemetryReceivedEvent(template.eventId(), template.deviceId(), template.sourceId(),
                template.timestamp(), metrics, template.receivedAt(), template.eventVersion(), template.correlationId(),
                template.producer(), template.accountId(), template.occurredAt(), template.processingContext());
        handler.process(imported);
        long before = jdbc.queryForObject("select count(*) from telemetry_event_outbox where topic='telemetry.validated.v1'", Long.class);
        assertThat(validatedReconciliation.reconcileMissingValidatedEvents(Instant.now().plusSeconds(3600)).enqueued()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from telemetry_event_outbox where topic='telemetry.validated.v1'", Long.class)).isEqualTo(before);
    }

    @Test
    void failingDerivedVariableDoesNotDiscardValidSiblingOutput() {
        var original = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        var valid = original.bindings().getFirst();
        var badContract = new VariableContract(UUID.randomUUID(), 1, "badTemperature", "Bad temperature", "DERIVED", "C", "v1",
                valid.contract().inputs(), new VariableExpression("DIVIDE", null, null, VariableExpression.input("x"), VariableExpression.constant(0)), null, null);
        var snapshot = new SourceProcessingConfigEvent(original.eventId(), "v1", original.accountId(), original.deviceId(), original.dataSourceId(),
                original.sourceId(), 1, original.digest(), true, List.of(valid, new SourceProcessingConfigEvent.Binding(badContract, valid.inputPaths())), Instant.now(), "device-service");
        bundles.apply(snapshot);
        var incoming = event(snapshot, Instant.now().minusSeconds(2), false);
        assertThat(handler.process(incoming)).isEqualTo(2);
        assertThat(queries.latest(snapshot.deviceId(), snapshot.dataSourceId(), valid.contract().variableId()).points().getFirst().value()).isEqualTo(22.7);
        assertThat(queries.latest(snapshot.deviceId(), snapshot.dataSourceId(), badContract.variableId()).points()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from variable_measurement_lineage where source_event_id=?", Integer.class, incoming.eventId())).isEqualTo(2);
    }

    @Test
    void disabledGenerationRetainsRecordedVariableHistory() {
        var snapshot = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        bundles.apply(snapshot);
        handler.process(event(snapshot, Instant.now().minusSeconds(3), false));
        var disabled = new SourceProcessingConfigEvent(UUID.randomUUID(), "v1", snapshot.accountId(), snapshot.deviceId(), snapshot.dataSourceId(),
                snapshot.sourceId(), 2, "b".repeat(64), false, List.of(), Instant.now(), "device-service");
        bundles.apply(disabled);
        UUID variable = snapshot.bindings().getFirst().contract().variableId();
        assertThat(queries.descriptor(snapshot.deviceId(), snapshot.dataSourceId(), variable).enabled()).isFalse();
        assertThat(queries.latest(snapshot.deviceId(), snapshot.dataSourceId(), variable).points().getFirst().value()).isEqualTo(22.7);
        assertThat(queries.read(snapshot.deviceId(), snapshot.dataSourceId(), variable, Instant.now().minusSeconds(60), Instant.now(), 10, null, 60, "AVG").points()).hasSize(1);
    }

    @Test
    void previewMatchesLiveAndConflictingBundleIsRejected() {
        var snapshot = bundle(1, UUID.randomUUID(), UUID.randomUUID(), 0.8);
        var contract = snapshot.bindings().getFirst().contract();
        var result = new VariableEvaluator().evaluate(contract, Map.of("x", 235.0));
        assertThat(result.value()).isEqualTo(22.7);
        assertThat(result.steps()).extracting(VariableEvaluator.Step::value).contains(23.5, 22.7);
        assertThat(new VariableEvaluator().evaluate(contract, Map.of()).status()).isEqualTo("MISSING_INPUT");
        bundles.apply(snapshot);
        var conflict = new SourceProcessingConfigEvent(snapshot.eventId(), "v1", snapshot.accountId(), snapshot.deviceId(),
                snapshot.dataSourceId(), snapshot.sourceId(), 1, "b".repeat(64), true, snapshot.bindings(), Instant.now(), "device-service");
        assertThatThrownBy(() -> bundles.apply(conflict)).isInstanceOf(IllegalArgumentException.class);
    }

    protected SourceProcessingConfigEvent bundle(int revision, UUID device, UUID source, double offset) {
        var ast = new VariableExpression("SUBTRACT", null, null, new VariableExpression("MULTIPLY", null, null,
                VariableExpression.input("x"), VariableExpression.constant(0.1)), VariableExpression.constant(offset));
        var contract = new VariableContract(UUID.randomUUID(), revision, "chamberTemperature", "Chamber temperature",
                "DERIVED", "C", "v1", Map.of("x", new VariableContract.Input("C", null, null)), ast, -50.0, 150.0);
        return new SourceProcessingConfigEvent(UUID.randomUUID(), "v1", UUID.randomUUID(), device, source, "variable-" + source,
                revision, "a".repeat(64), true, List.of(new SourceProcessingConfigEvent.Binding(contract,
                Map.of("x", "sensorTemperatureRaw"))), Instant.now(), "device-service");
    }

    private SourceProcessingConfigEvent revised(SourceProcessingConfigEvent source, int revision, double offset) {
        var template = bundle(revision, source.deviceId(), source.dataSourceId(), offset);
        var contract = template.bindings().getFirst().contract();
        var old = source.bindings().getFirst().contract();
        var updated = new VariableContract(old.variableId(), revision, old.key(), old.label(), old.origin(), old.unit(),
                "v1", old.inputs(), contract.expression(), old.minimum(), old.maximum());
        return new SourceProcessingConfigEvent(UUID.randomUUID(), "v1", source.accountId(), source.deviceId(), source.dataSourceId(),
                source.sourceId(), revision, "b".repeat(64), true, List.of(new SourceProcessingConfigEvent.Binding(updated,
                Map.of("x", "sensorTemperatureRaw"))), Instant.now(), "device-service");
    }

    protected TelemetryReceivedEvent event(SourceProcessingConfigEvent bundle, Instant time, boolean backfill) {
        return new TelemetryReceivedEvent(UUID.randomUUID(), bundle.deviceId(), bundle.sourceId(), time,
                Map.of("configuredFields", Map.of("sensorTemperatureRaw", Map.of("value", 235, "type", "number",
                        "label", "Raw temperature", "unit", "C", "display", true))), Instant.now(),
                "v1", null, "ingestion-service", bundle.accountId(), time,
                new ProcessingContext(bundle.dataSourceId(), bundle.generation(), bundle.digest(), "v1", backfill));
    }
}
