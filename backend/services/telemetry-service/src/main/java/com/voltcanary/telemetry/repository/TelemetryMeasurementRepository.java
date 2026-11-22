package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.QualityFlag;
import com.voltcanary.telemetry.domain.TelemetryMeasurement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class TelemetryMeasurementRepository {

    private final JdbcTemplate jdbcTemplate;

    public String findUnit(UUID deviceId, String metric) {
        return jdbcTemplate
            .query(
                "select unit from telemetry.measurements where device_id=? and metric_name=? order by recorded_at desc limit 1",
                (row, index) -> row.getString("unit"),
                deviceId,
                metric
            )
            .stream()
            .findFirst()
            .orElse("");
    }

    @Transactional
    public int insertAll(Collection<TelemetryMeasurement> measurements) {
        List<TelemetryMeasurement> batch = List.copyOf(measurements);
        if (batch.isEmpty()) return 0;
        return jdbcTemplate.execute(
            (ConnectionCallback<Integer>) connection -> {
                boolean postgres = connection
                    .getMetaData()
                    .getDatabaseProductName()
                    .equalsIgnoreCase("PostgreSQL");
                int total = 0;
                for (int start = 0; start < batch.size(); start += 512) {
                    List<TelemetryMeasurement> chunk = batch.subList(
                        start,
                        Math.min(batch.size(), start + 512)
                    );
                    if (postgres) {
                        String tuples = String.join(
                            ",",
                            Collections.nCopies(chunk.size(), "(?,?,?,?,?,?,?,?,?)")
                        );
                        String sql =
                            "with inserted as (insert into telemetry.measurements " +
                            "(device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at) values " +
                            tuples +
                            " on conflict do nothing returning 1) select count(*) from inserted";
                        try (var statement = connection.prepareStatement(sql)) {
                            for (int index = 0; index < chunk.size(); index++) bind(
                                statement,
                                index * 9,
                                chunk.get(index)
                            );
                            try (var result = statement.executeQuery()) {
                                result.next();
                                total += result.getInt(1);
                            }
                        }
                    } else {
                        String sql =
                            "merge into telemetry.measurements target using (values (?,?,?,?,?,?,?,?,?)) " +
                            "incoming(device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at) " +
                            "on target.device_id=incoming.device_id and target.source_id=incoming.source_id " +
                            "and target.recorded_at=incoming.recorded_at and target.metric_name=incoming.metric_name " +
                            "when not matched then insert (device_id,source_id,recorded_at,metric_name,metric_value,unit,quality,received_at,created_at) " +
                            "values(incoming.device_id,incoming.source_id,incoming.recorded_at,incoming.metric_name,incoming.metric_value,incoming.unit,incoming.quality,incoming.received_at,incoming.created_at)";
                        try (var statement = connection.prepareStatement(sql)) {
                            for (var measurement : chunk) {
                                bind(statement, 0, measurement);
                                statement.addBatch();
                            }
                            for (int count : statement.executeBatch()) {
                                if (count < 0) throw new SQLException(
                                    "Database did not report an exact telemetry insert count"
                                );
                                total += count;
                            }
                        }
                    }
                }
                return total;
            }
        );
    }

    private static void bind(
        PreparedStatement statement,
        int offset,
        TelemetryMeasurement measurement
    ) throws SQLException {
        statement.setObject(offset + 1, measurement.deviceId());
        statement.setString(offset + 2, measurement.sourceId());
        statement.setTimestamp(offset + 3, Timestamp.from(measurement.recordedAt()));
        statement.setString(offset + 4, measurement.metricName());
        statement.setDouble(offset + 5, measurement.metricValue());
        statement.setString(offset + 6, measurement.unit());
        statement.setString(offset + 7, measurement.quality().name());
        statement.setTimestamp(offset + 8, Timestamp.from(measurement.receivedAt()));
        statement.setTimestamp(offset + 9, Timestamp.from(measurement.createdAt()));
    }

    public Optional<Instant> latestTimestamp(UUID deviceId) {
        List<Instant> timestamps = jdbcTemplate.query(
            "select max(recorded_at) as recorded_at from telemetry.measurements where device_id = ?",
            (rs, rowNum) -> toInstant(rs, "recorded_at"),
            deviceId
        );
        return timestamps
            .stream()
            .filter(timestamp -> timestamp != null)
            .findFirst();
    }

    public List<TelemetryMeasurement> findByDeviceIdAndTimestamp(UUID deviceId, Instant timestamp) {
        return jdbcTemplate.query(
            """
            select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at
            from telemetry.measurements
            where device_id = ? and recorded_at = ?
            order by metric_name
            """,
            this::mapRow,
            deviceId,
            Timestamp.from(timestamp)
        );
    }

    public List<TelemetryMeasurement> findSeries(
        UUID deviceId,
        String metricName,
        Instant from,
        Instant to
    ) {
        return jdbcTemplate.query(
            """
            select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at
            from telemetry.measurements
            where device_id = ?
              and metric_name = ?
              and recorded_at >= ?
              and recorded_at <= ?
            order by recorded_at asc
            limit 1000
            """,
            this::mapRow,
            deviceId,
            metricName,
            Timestamp.from(from),
            Timestamp.from(to)
        );
    }

    public List<TelemetryMeasurement> findSeriesRaw(
        UUID deviceId,
        String metricName,
        Instant from,
        Instant to
    ) {
        return jdbcTemplate.query(
            """
            select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at
            from telemetry.measurements
            where device_id = ?
              and metric_name = ?
              and recorded_at >= ?
              and recorded_at <= ?
            order by recorded_at asc
            """,
            this::mapRow,
            deviceId,
            metricName,
            Timestamp.from(from),
            Timestamp.from(to)
        );
    }

    public List<TelemetryMeasurement> findSeriesPage(
        UUID deviceId,
        String metricName,
        Instant from,
        Instant to,
        Instant cursor,
        String cursorSourceId,
        int limit
    ) {
        Instant effectiveCursor = cursor == null ? from.minusNanos(1) : cursor;
        return jdbcTemplate.query(
            """
            select device_id, source_id, recorded_at, metric_name, metric_value, unit, quality, received_at, created_at
            from telemetry.measurements
            where device_id = ?
              and metric_name = ?
              and recorded_at >= ?
              and recorded_at <= ?
              and (recorded_at > ? or (recorded_at = ? and source_id > ?))
            order by recorded_at asc, source_id asc
            limit ?
            """,
            this::mapRow,
            deviceId,
            metricName,
            Timestamp.from(from),
            Timestamp.from(to),
            Timestamp.from(effectiveCursor),
            Timestamp.from(effectiveCursor),
            cursorSourceId,
            limit
        );
    }

    public List<String> findMetricNamesByDeviceId(UUID deviceId) {
        return jdbcTemplate.queryForList(
            """
            select distinct metric_name
            from telemetry.measurements
            where device_id = ?
            order by metric_name
            """,
            String.class,
            deviceId
        );
    }

    public long countByDeviceSourceTimestamp(UUID deviceId, String sourceId, Instant timestamp) {
        Long count = jdbcTemplate.queryForObject(
            """
            select count(*)
            from telemetry.measurements
            where device_id = ? and source_id = ? and recorded_at = ?
            """,
            Long.class,
            deviceId,
            sourceId,
            Timestamp.from(timestamp)
        );
        return count == null ? 0 : count;
    }

    public List<String> findMetricNamesByDeviceIdAndRange(UUID deviceId, Instant from, Instant to) {
        return jdbcTemplate.queryForList(
            """
            select distinct metric_name from telemetry.measurements
            where device_id=? and recorded_at>=? and recorded_at<=? order by metric_name
            """,
            String.class,
            deviceId,
            Timestamp.from(from),
            Timestamp.from(to)
        );
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
            toInstant(rs, "created_at")
        );
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
