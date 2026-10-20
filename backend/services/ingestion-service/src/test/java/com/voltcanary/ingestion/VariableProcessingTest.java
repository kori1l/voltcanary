package com.voltcanary.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.common.events.telemetry.VariableExpression;
import com.voltcanary.ingestion.config.VariableProcessingProperties;
import com.voltcanary.ingestion.source.ResolvedSource;
import com.voltcanary.ingestion.source.SourceProcessingProjection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class VariableProcessingTest {
    @Test
    void eachReplicaRequiresExactCompatibleSnapshotAndNeverRegresses() {
        var properties = new VariableProcessingProperties();
        properties.setEnabled(true);
        var first = new SourceProcessingProjection(properties);
        var second = new SourceProcessingProjection(properties);
        var bundle = bundle();
        var source = source(bundle);
        assertThatThrownBy(() -> first.pin(source)).isInstanceOf(ResponseStatusException.class);
        first.apply(bundle);
        assertThat(first.pin(source).generation()).isEqualTo(1);
        assertThatThrownBy(() -> second.pin(source)).isInstanceOf(ResponseStatusException.class);
        second.apply(bundle);
        assertThat(second.pin(source)).isEqualTo(first.pin(source));
        first.apply(bundle);
        var conflict = new SourceProcessingConfigEvent(UUID.randomUUID(), "v1", bundle.accountId(), bundle.deviceId(),
                bundle.dataSourceId(), bundle.sourceId(), 1, "b".repeat(64), true, bundle.bindings(), Instant.now(), "device-service");
        assertThatThrownBy(() -> first.apply(conflict)).isInstanceOf(IllegalArgumentException.class);
        properties.setEnabled(false);
        assertThatThrownBy(() -> second.pin(source)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validatesDeclaredRawInputWithoutCelsiusOutputBounds() {
        var properties = new VariableProcessingProperties();
        properties.setEnabled(true);
        var projection = new SourceProcessingProjection(properties);
        var bundle = bundle();
        projection.apply(bundle);
        projection.validateInputs(source(bundle), Map.of("configuredFields", Map.of("raw", Map.of("value", 235,
                "type", "number", "unit", "C", "label", "Raw temperature", "display", true))));
        assertThatThrownBy(() -> projection.validateInputs(source(bundle),
                Map.of("configuredFields", Map.of("raw", Map.of("value", Double.NaN)))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void oldWireMessagesStillDeserializeWithoutProcessingContext() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var original = new TelemetryReceivedEvent(UUID.randomUUID(), UUID.randomUUID(), "legacy",
                Instant.now(), Map.of("temperatureC", 3), Instant.now());
        var json = mapper.valueToTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("processingContext");
        assertThat(mapper.treeToValue(json, TelemetryReceivedEvent.class).processingContext()).isNull();
    }

    private SourceProcessingConfigEvent bundle() {
        var contract = new VariableContract(UUID.randomUUID(), 1, "chamber", "Temperature", "DERIVED", "C", "v1",
                Map.of("x", new VariableContract.Input("C", null, null)), VariableExpression.input("x"), null, null);
        return new SourceProcessingConfigEvent(UUID.randomUUID(), "v1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "source", 1, "a".repeat(64), true,
                List.of(new SourceProcessingConfigEvent.Binding(contract, Map.of("x", "raw"))), Instant.now(), "device-service");
    }

    private ResolvedSource source(SourceProcessingConfigEvent bundle) {
        return new ResolvedSource(bundle.accountId(), bundle.deviceId(), bundle.dataSourceId(), bundle.sourceId(),
                "SENSOR", "REST", "ACTIVE", Map.of("processingGeneration", bundle.generation(), "processingDigest", bundle.digest()));
    }
}
