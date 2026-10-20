package com.voltcanary.telemetry.variable;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.config.VariableProcessingProperties;
import com.voltcanary.telemetry.service.TelemetryEventProcessor;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfiguredTelemetryHandler {
    private final ProcessingBundles bundles;
    private final VariableProcessingProperties properties;
    private final TelemetryEventProcessor legacy;
    private final VariableTelemetry variables;
    private final JdbcTemplate jdbc;

    public ConfiguredTelemetryHandler(ProcessingBundles bundles, VariableProcessingProperties properties,
            TelemetryEventProcessor legacy, VariableTelemetry variables, JdbcTemplate jdbc) {
        this.bundles = bundles;
        this.properties = properties;
        this.legacy = legacy;
        this.variables = variables;
        this.jdbc = jdbc;
    }

    @Transactional
    public int process(TelemetryReceivedEvent event) {
        if (event.processingContext() == null) return legacy.process(event);
        var context = event.processingContext();
        if (event.eventId() == null || event.accountId() == null || context.dataSourceId() == null
                || context.generation() < 1 || context.digest() == null || !context.digest().matches("[a-f0-9]{64}")
                || !"v1".equals(context.inputSchemaVersion())) {
            throw new IllegalArgumentException("Invalid pinned processing context");
        }
        var bundle = bundles.find(context);
        if (!properties.isEnabled() || bundle.isEmpty()) {
            park(event, properties.isEnabled() ? "BUNDLE_UNAVAILABLE" : "PROCESSING_DISABLED");
            return 0;
        }
        var snapshot = bundle.get();
        if (!snapshot.accountId().equals(event.accountId()) || !snapshot.deviceId().equals(event.deviceId())
                || !snapshot.sourceId().equals(event.sourceId())) {
            throw new IllegalArgumentException("Pinned processing source association mismatch");
        }
        if (variables.alreadyProcessed(event)) {
            jdbc.update("update variable_pending_events set status='PROCESSED',updated_at=? where event_id=?",
                    Timestamp.from(Instant.now()), event.eventId());
            return 0;
        }
        boolean duplicateTimestamp = jdbc.queryForObject("""
                select count(*) from telemetry.measurements where device_id=? and source_id=? and recorded_at=?
                """, Integer.class, event.deviceId(), event.sourceId(), Timestamp.from(event.timestamp())) > 0;
        var result = duplicateTimestamp ? new TelemetryEventProcessor.InputOutcome(0, false) : legacy.processInput(event);
        int observed = result.inserted();
        int computed = variables.materialize(event, snapshot, result.eligibleForDerived());
        jdbc.update("update variable_pending_events set status='PROCESSED',updated_at=? where event_id=?",
                Timestamp.from(Instant.now()), event.eventId());
        return observed + computed;
    }

    private void park(TelemetryReceivedEvent event, String reason) {
        Integer present = jdbc.queryForObject("select count(*) from variable_pending_events where event_id=?",
                Integer.class, event.eventId());
        if (present > 0) return;
        if (jdbc.queryForObject("select count(*) from variable_pending_events where status='PENDING'", Integer.class) >= 10000) {
            throw new IllegalStateException("Variable pending event capacity exceeded; retry required");
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into variable_pending_events(event_id,payload_json,attempts,status,diagnostic,created_at,updated_at)
                values(?,?,0,'PENDING',?,?,?)
                """, event.eventId(), bundles.json(event), reason, now, now);
    }
}
