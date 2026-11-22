package com.voltcanary.telemetry.observation;

import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** First actual accepted write assigns the journal generation and cutover; reads never create identity. */
@Component
@RequiredArgsConstructor
public class ObservationGenerations {

    public record Generation(UUID id, long startCheckpoint, Instant startedAt) {
        public Generation {
            if (
                id == null || startCheckpoint < 0 || startedAt == null
            ) throw new ObservationCaptureUnavailable("OBSERVATION_GENERATION_INVALID");
        }
    }

    private final JdbcTemplate jdbc;

    public Optional<Generation> find(UUID binding) {
        return jdbc
            .query(
                "SELECT input_generation,generation_start_checkpoint,generation_started_at,head_checkpoint FROM cfg_observation_stream WHERE binding_id=?",
                (row, index) -> read(row),
                binding
            )
            .stream()
            .findFirst()
            .flatMap(value -> value);
    }

    Generation begin(SourceBindingSchemaPin pin, long head, Instant now) {
        if (
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) throw new IllegalStateException(
            "Observation generation requires the capture transaction"
        );
        var rows = jdbc.query(
            "SELECT * FROM cfg_observation_stream WHERE binding_id=? FOR UPDATE",
            (row, index) -> {
                if (
                    !pin.accountId().equals(row.getObject("account_id", UUID.class)) ||
                    !pin.deviceId().equals(row.getObject("device_id", UUID.class)) ||
                    head != row.getLong("head_checkpoint")
                ) throw new ObservationCaptureUnavailable("OBSERVATION_SCOPE_CHANGED");
                return read(row);
            },
            pin.bindingId()
        );
        if (rows.isEmpty()) throw new ObservationCaptureUnavailable("OBSERVATION_SCHEMA_INVALID");
        if (rows.getFirst().isPresent()) return rows.getFirst().get();
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (id.equals(pin.snapshotId()) || id.equals(pin.bindingId()));
        int changed = jdbc.update(
            "UPDATE cfg_observation_stream SET input_generation=?,generation_start_checkpoint=?,generation_started_at=? WHERE binding_id=? AND input_generation IS NULL",
            id,
            head,
            Timestamp.from(now),
            pin.bindingId()
        );
        if (changed != 1) throw new ObservationCaptureUnavailable("OBSERVATION_GENERATION_CHANGED");
        return new Generation(id, head, now);
    }

    private static Optional<Generation> read(java.sql.ResultSet row) throws java.sql.SQLException {
        UUID id = row.getObject("input_generation", UUID.class);
        Long start = row.getObject("generation_start_checkpoint", Long.class);
        OffsetDateTime time = row.getObject("generation_started_at", OffsetDateTime.class);
        if (id == null && start == null && time == null) return Optional.empty();
        if (id == null || start == null || time == null || start > row.getLong("head_checkpoint")) {
            throw new ObservationCaptureUnavailable("OBSERVATION_GENERATION_INVALID");
        }
        return Optional.of(new Generation(id, start, time.toInstant()));
    }
}
