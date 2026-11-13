package com.voltcanary.telemetry.variable;

import com.voltcanary.common.events.device.SourceProcessingConfigEvent;
import com.voltcanary.common.events.telemetry.ConfiguredFields;
import com.voltcanary.common.events.telemetry.MeasurementsAcceptedEvent;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.telemetry.domain.QualityFlag;
import com.voltcanary.telemetry.domain.TelemetryMeasurement;
import com.voltcanary.telemetry.producer.TelemetryOutboxService;
import com.voltcanary.telemetry.repository.TelemetryMeasurementRepository;
import com.voltcanary.telemetry.variable.domain.VariableEvaluator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VariableTelemetry {
    private final com.voltcanary.common.web.JdbcDeviceFence fence;
    private final JdbcTemplate jdbc;
    private final ProcessingBundles bundles;
    private final TelemetryMeasurementRepository measurements;
    private final TelemetryOutboxService outbox;
    private final VariableEvaluator evaluator = new VariableEvaluator();

    public VariableTelemetry(JdbcTemplate jdbc, ProcessingBundles bundles,
            TelemetryMeasurementRepository measurements, TelemetryOutboxService outbox) {
        this.jdbc = jdbc;
        this.fence = new com.voltcanary.common.web.JdbcDeviceFence(jdbc);
        this.bundles = bundles;
        this.measurements = measurements;
        this.outbox = outbox;
    }

    public boolean alreadyProcessed(TelemetryReceivedEvent event) {
        // Serialize all configured processing of this source/generation, including duplicate deliveries.
        jdbc.queryForObject("select source_id from variable_source_locks where source_id=? for update",
                UUID.class, event.processingContext().dataSourceId());
        return jdbc.queryForObject("select count(*) from processed_variable_events where event_id=?",
                Integer.class, event.eventId()) > 0;
    }

    /** Runs inside the handler transaction after the legacy input validation/persistence succeeds. */
    public int materialize(TelemetryReceivedEvent event, SourceProcessingConfigEvent bundle, boolean inputAccepted) {
        if (!fence.writable(event.deviceId())) return 0;
        List<MeasurementsAcceptedEvent.Measurement> accepted = new ArrayList<>();
        List<TelemetryMeasurement> rows = new ArrayList<>();
        if (inputAccepted && bundle.enabled()) {
            var configured = ConfiguredFields.values(event.metrics());
            for (var binding : bundle.bindings()) {
                Map<String, Double> inputs = new LinkedHashMap<>();
                for (var entry : binding.inputPaths().entrySet()) {
                    Object value = event.metrics().get(entry.getValue());
                    if (value == null && configured.containsKey(entry.getValue())) value = configured.get(entry.getValue()).get("value");
                    if (value instanceof Number number) inputs.put(entry.getKey(), number.doubleValue());
                }
                var result = evaluator.evaluate(binding.contract(), inputs);
                String token = "var_" + binding.contract().variableId().toString().replace("-", "");
                UUID lineage = UUID.nameUUIDFromBytes((event.eventId() + ":" + binding.contract().variableId()
                        + ":" + binding.contract().revision()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                persistLineage(event, bundle, binding, result, token, lineage, inputs);
                if (!"OK".equals(result.status())) continue;
                rows.add(new TelemetryMeasurement(event.deviceId(), event.sourceId(), event.timestamp(), token,
                        result.value(), result.unit(), QualityFlag.OK, event.receivedAt(), Instant.now()));
                accepted.add(new MeasurementsAcceptedEvent.Measurement(binding.contract().variableId(),
                        binding.contract().revision(), binding.contract().key(), binding.contract().origin(),
                        result.value(), result.unit(), "OK", lineage));
            }
        }
        int inserted = measurements.insertAll(rows);
        jdbc.update("insert into processed_variable_events(event_id,data_source_id,processed_at) values(?,?,?)",
                event.eventId(), bundle.dataSourceId(), Timestamp.from(Instant.now()));
        if (!accepted.isEmpty()) {
            UUID eventId = UUID.nameUUIDFromBytes(("variables:" + event.eventId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var outgoing = new MeasurementsAcceptedEvent(eventId, "v1", event.eventId(), event.accountId(),
                    event.deviceId(), bundle.dataSourceId(), event.sourceId(), event.timestamp(), event.receivedAt(),
                    event.processingContext(), event.processingContext().historicalImport() ? "BACKFILL" : "LIVE",
                    accepted, Instant.now(), "telemetry-service");
            outbox.enqueue("variables:" + event.eventId(), "telemetry.measurements.accepted.v1",
                    event.deviceId().toString(), outgoing);
        }
        return inserted;
    }

    private void persistLineage(TelemetryReceivedEvent event, SourceProcessingConfigEvent bundle,
            SourceProcessingConfigEvent.Binding binding, VariableEvaluator.Result result, String token,
            UUID lineage, Map<String, Double> inputs) {
        jdbc.update("""
                insert into variable_measurement_lineage
                (id,source_event_id,account_id,device_id,data_source_id,external_source_id,variable_id,
                 revision,bundle_generation,bundle_digest,evaluator_version,metric_token,unit,
                 recorded_at,received_at,input_snapshot_json,status,eligibility)
                values(?,?,?,?,?,?,?,?,?,?,'v1',?,?,?,?,?,?,?)
                """, lineage, event.eventId(), event.accountId(), event.deviceId(), bundle.dataSourceId(),
                event.sourceId(), binding.contract().variableId(), binding.contract().revision(), bundle.generation(),
                bundle.digest(), token, binding.contract().unit(), Timestamp.from(event.timestamp()),
                Timestamp.from(event.receivedAt()), bundles.json(inputs), result.status(),
                event.processingContext().historicalImport() ? "BACKFILL" : "LIVE");
    }
}
