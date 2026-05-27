package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.TelemetryReading;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryReadingRepository {

    private final JdbcTemplate jdbcTemplate;

    public TelemetryReadingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(TelemetryReading reading) {
        jdbcTemplate.update("""
                insert into telemetry_readings (id, device_id, source_id, recorded_at, received_at, metrics_json)
                values (?, ?, ?, ?, ?, ?)
                """,
                reading.getId(),
                reading.getDeviceId(),
                reading.getSourceId(),
                Timestamp.from(reading.getRecordedAt()),
                Timestamp.from(reading.getReceivedAt()),
                reading.getMetricsJson());
    }

    public List<TelemetryReading> findTop100ByDeviceIdOrderByRecordedAtDesc(UUID deviceId) {
        return jdbcTemplate.query("""
                select id, device_id, source_id, recorded_at, received_at, metrics_json
                from telemetry_readings
                where device_id = ?
                order by recorded_at desc
                limit 100
                """, this::mapRow, deviceId);
    }

    public Optional<TelemetryReading> findFirstByDeviceIdOrderByRecordedAtDesc(UUID deviceId) {
        return jdbcTemplate.query("""
                select id, device_id, source_id, recorded_at, received_at, metrics_json
                from telemetry_readings
                where device_id = ?
                order by recorded_at desc
                limit 1
                """, this::mapRow, deviceId).stream().findFirst();
    }

    private TelemetryReading mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TelemetryReading(
                rs.getObject("id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getString("source_id"),
                toInstant(rs, "recorded_at"),
                toInstant(rs, "received_at"),
                rs.getString("metrics_json"));
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
