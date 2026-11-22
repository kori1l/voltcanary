package com.voltcanary.telemetry.variable;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.config.VariableProcessingProperties;
import com.voltcanary.telemetry.service.TelemetryEventProcessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfiguredTelemetryHandler {

    private final ProcessingBundles bundles;
    private final VariableProcessingProperties properties;
    private final TelemetryEventProcessor legacy;
    private final VariableTelemetry variables;
    private final ConfiguredTelemetryStore store;
    private final com.voltcanary.telemetry.observation.ObservationCapture observations;

    public ConfiguredTelemetryHandler(
        ProcessingBundles bundles,
        VariableProcessingProperties properties,
        TelemetryEventProcessor legacy,
        VariableTelemetry variables,
        ConfiguredTelemetryStore store,
        com.voltcanary.telemetry.observation.ObservationCapture observations
    ) {
        this.bundles = bundles;
        this.properties = properties;
        this.legacy = legacy;
        this.variables = variables;
        this.store = store;
        this.observations = observations;
    }

    @Transactional
    public int process(TelemetryReceivedEvent event) {
        if (!store.writable(event.deviceId())) return 0;
        var observation = observations.capture(event);
        if (event.processingContext() == null) {
            // Accepted event identity is independent of the legacy measurement timestamp key.
            // Avoid a legacy unique-key abort rolling back the second same-time observation.
            if (
                observation !=
                    com.voltcanary.telemetry.observation.ObservationCapture.Outcome.SKIPPED &&
                store.hasMeasurement(event.deviceId(), event.sourceId(), event.timestamp())
            ) return 0;
            return legacy.process(event);
        }
        var context = event.processingContext();
        if (
            event.eventId() == null ||
            event.accountId() == null ||
            context.dataSourceId() == null ||
            context.generation() < 1 ||
            context.digest() == null ||
            !context.digest().matches("[a-f0-9]{64}") ||
            !"v1".equals(context.inputSchemaVersion())
        ) {
            throw new IllegalArgumentException("Invalid pinned processing context");
        }
        var bundle = bundles.find(context);
        if (!properties.isEnabled() || bundle.isEmpty()) {
            store.park(
                event.eventId(),
                bundles.json(event),
                properties.isEnabled() ? "BUNDLE_UNAVAILABLE" : "PROCESSING_DISABLED"
            );
            return 0;
        }
        var snapshot = bundle.get();
        if (
            !snapshot.accountId().equals(event.accountId()) ||
            !snapshot.deviceId().equals(event.deviceId()) ||
            !snapshot.sourceId().equals(event.sourceId())
        ) {
            throw new IllegalArgumentException("Pinned processing source association mismatch");
        }
        if (variables.alreadyProcessed(event)) {
            store.processed(event.eventId());
            return 0;
        }
        boolean duplicateTimestamp = store.hasMeasurement(
            event.deviceId(),
            event.sourceId(),
            event.timestamp()
        );
        var result = duplicateTimestamp
            ? new TelemetryEventProcessor.InputOutcome(0, false)
            : legacy.processInput(event);
        int observed = result.inserted();
        int computed = variables.materialize(event, snapshot, result.eligibleForDerived());
        store.processed(event.eventId());
        return observed + computed;
    }
}
