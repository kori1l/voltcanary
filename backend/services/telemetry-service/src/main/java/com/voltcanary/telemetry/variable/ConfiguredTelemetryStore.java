package com.voltcanary.telemetry.variable;

import com.voltcanary.common.web.JdbcDeviceFence;
import com.voltcanary.telemetry.config.VariableProcessingProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Joins the handler transaction; the guard makes pending capacity atomic across partitions. */
@Repository
public class ConfiguredTelemetryStore {

    private final JdbcTemplate jdbc;
    private final JdbcDeviceFence fence;
    private final VariableProcessingProperties properties;

    public ConfiguredTelemetryStore(JdbcTemplate jdbc, VariableProcessingProperties properties) {
        this.jdbc = jdbc;
        this.fence = new JdbcDeviceFence(jdbc);
        this.properties = properties;
    }

    public boolean writable(UUID device) {
        return device == null || fence.writable(device);
    }

    public boolean hasMeasurement(UUID device, String source, Instant recordedAt) {
        return Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
                select exists(select 1 from telemetry.measurements
                where device_id=? and source_id=? and recorded_at=?)
                """,
                Boolean.class,
                device,
                source,
                Timestamp.from(recordedAt)
            )
        );
    }

    public void processed(UUID event) {
        jdbc.update(
            "update variable_pending_events set status='PROCESSED',updated_at=? where event_id=?",
            Timestamp.from(Instant.now()),
            event
        );
    }

    public void park(UUID event, String payload, String reason) {
        jdbc.queryForObject(
            "select id from variable_pending_capacity_guard where id=1 for update",
            Integer.class
        );
        if (
            jdbc.queryForObject(
                "select count(*) from variable_pending_events where event_id=?",
                Integer.class,
                event
            ) > 0
        ) {
            return;
        }
        if (
            jdbc.queryForObject(
                "select count(*) from variable_pending_events where status='PENDING'",
                Integer.class
            ) >= properties.getPendingEventCapacity()
        ) {
            throw new IllegalStateException(
                "Variable pending event capacity exceeded; retry required"
            );
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
            """
            insert into variable_pending_events(event_id,payload_json,attempts,status,diagnostic,created_at,updated_at)
            values(?,?,0,'PENDING',?,?,?)
            """,
            event,
            payload,
            reason,
            now,
            now
        );
    }
}
