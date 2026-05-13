package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.QualityFlag;
import com.voltcanary.telemetry.domain.TelemetryMeasurement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryMeasurementRepository {

    private final JdbcTemplate jdbcTemplate;

    public TelemetryMeasurementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertAll(Collection<TelemetryMeasurement> measurements) {
        int inserted = 0;
        for (TelemetryMeasurement measurement : measurements) {
            try {
                inserted += jdbcTemplate.update("""
                        insert into telemetry.measurements
                            (device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        measurement.deviceId(),
                        measurement.sourceId(),
                        Timestamp.from(measurement.recordedAt()),
                        measurement.metricName(),
                        measurement.metricValue(),
                        measurement.unit(),
                        measurement.quality().name(),
                        Timestamp.from(measurement.createdAt()));
            } catch (DuplicateKeyException duplicate) {
                // Idempotency at the storage boundary: already persisted metric rows are ignored.
            }
        }
        return inserted;
    }

    public Optional<Instant> latestTimestamp(UUID deviceId) {
        List<Instant> timestamps = jdbcTemplate.query(
                "select max(recorded_at) as recorded_at from telemetry.measurements where device_id = ?",
                (rs, rowNum) -> toInstant(rs, "recorded_at"),
                deviceId);
        return timestamps.stream().filter(timestamp -> timestamp != null).findFirst();
    }

    public List<TelemetryMeasurement> findByDeviceIdAndTimestamp(UUID deviceId, Instant timestamp) {
        return jdbcTemplate.query("""
                select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, created_at
                from telemetry.measurements
                where device_id = ? and recorded_at = ?
                order by metric_name
                """, this::mapRow, deviceId, Timestamp.from(timestamp));
    }

    public List<TelemetryMeasurement> findSeries(UUID deviceId, String metricName, Instant from, Instant to) {
        return jdbcTemplate.query("""
                select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, created_at
                from telemetry.measurements
                where device_id = ?
                  and metric_name = ?
                  and recorded_at >= ?
                  and recorded_at <= ?
                order by recorded_at asc
                limit 1000
                """, this::mapRow, deviceId, metricName, Timestamp.from(from), Timestamp.from(to));
    }

    public long countByDeviceSourceTimestamp(UUID deviceId, String sourceId, Instant timestamp) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from telemetry.measurements
                where device_id = ? and source_id = ? and recorded_at = ?
                """, Long.class, deviceId, sourceId, Timestamp.from(timestamp));
        return count == null ? 0 : count;
    }

    private TelemetryMeasurement mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TelemetryMeasurement(
                rs.getObject("device_id", UUID.class),
                rs.getString("source_id"),
                toInstant(rs, "recorded_at"),
                rs.getString("metric_name"),
                rs.getDouble("metric_value"),
                rs.getString("unit"),
                QualityFlag.valueOf(rs.getString("quality")),
                toInstant(rs, "created_at"));
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
