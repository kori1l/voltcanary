package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.QualityFlag;
import com.voltcanary.telemetry.domain.TelemetryMeasurement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryMeasurementRepository {

    private final JdbcTemplate jdbcTemplate;

    public TelemetryMeasurementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertAll(Collection<TelemetryMeasurement> measurements) {
        List<TelemetryMeasurement> batch = List.copyOf(measurements);
        if (batch.isEmpty()) {
            return 0;
        }
        try {
                int[] inserted = jdbcTemplate.batchUpdate("""
                    insert into telemetry.measurements
                        (device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    TelemetryMeasurement measurement = batch.get(i);
                    ps.setObject(1, measurement.deviceId());
                    ps.setString(2, measurement.sourceId());
                    ps.setTimestamp(3, Timestamp.from(measurement.recordedAt()));
                    ps.setString(4, measurement.metricName());
                    ps.setDouble(5, measurement.metricValue());
                    ps.setString(6, measurement.unit());
                    ps.setString(7, measurement.quality().name());
                    ps.setTimestamp(8, Timestamp.from(measurement.receivedAt()));
                    ps.setTimestamp(9, Timestamp.from(measurement.createdAt()));
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
            int total = 0;
            for (int count : inserted) {
                total += Math.max(count, 0);
            }
            return total;
        } catch (DataIntegrityViolationException duplicate) {
            return insertIndividuallyIgnoringDuplicates(batch);
        }
    }

    private int insertIndividuallyIgnoringDuplicates(Collection<TelemetryMeasurement> measurements) {
        int inserted = 0;
        for (TelemetryMeasurement measurement : measurements) {
            try {
                inserted += jdbcTemplate.update("""
                        insert into telemetry.measurements
                            (device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        measurement.deviceId(),
                        measurement.sourceId(),
                        Timestamp.from(measurement.recordedAt()),
                        measurement.metricName(),
                        measurement.metricValue(),
                        measurement.unit(),
                        measurement.quality().name(),
                        Timestamp.from(measurement.receivedAt()),
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
                select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at
                from telemetry.measurements
                where device_id = ? and recorded_at = ?
                order by metric_name
                """, this::mapRow, deviceId, Timestamp.from(timestamp));
    }

    public List<TelemetryMeasurement> findSeries(UUID deviceId, String metricName, Instant from, Instant to) {
        return jdbcTemplate.query("""
                select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at
                from telemetry.measurements
                where device_id = ?
                  and metric_name = ?
                  and recorded_at >= ?
                  and recorded_at <= ?
                order by recorded_at asc
                limit 1000
                """, this::mapRow, deviceId, metricName, Timestamp.from(from), Timestamp.from(to));
    }

    public List<TelemetryMeasurement> findSeriesRaw(UUID deviceId, String metricName, Instant from, Instant to) {
        return jdbcTemplate.query("""
                select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at
                from telemetry.measurements
                where device_id = ?
                  and metric_name = ?
                  and recorded_at >= ?
                  and recorded_at <= ?
                order by recorded_at asc
                """, this::mapRow, deviceId, metricName, Timestamp.from(from), Timestamp.from(to));
    }

    public List<TelemetryMeasurement> findSeriesPage(
            UUID deviceId,
            String metricName,
            Instant from,
            Instant to,
            Instant cursor,
            int limit) {
        Instant effectiveCursor = cursor == null ? from.minusNanos(1) : cursor;
        return jdbcTemplate.query("""
                select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at
                from telemetry.measurements
                where device_id = ?
                  and metric_name = ?
                  and recorded_at >= ?
                  and recorded_at <= ?
                  and recorded_at > ?
                order by recorded_at asc
                limit ?
                """, this::mapRow, deviceId, metricName, Timestamp.from(from), Timestamp.from(to), Timestamp.from(effectiveCursor), limit);
    }

    public List<String> findMetricNamesByDeviceId(UUID deviceId) {
        return jdbcTemplate.queryForList("""
                select distinct metric_name
                from telemetry.measurements
                where device_id = ?
                order by metric_name
                """, String.class, deviceId);
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
                toInstant(rs, "received_at"),
                toInstant(rs, "created_at"));
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
