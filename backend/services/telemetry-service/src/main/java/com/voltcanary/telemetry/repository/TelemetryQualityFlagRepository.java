package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.domain.QualityFlag;
import com.voltcanary.telemetry.domain.TelemetryQualityFlag;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryQualityFlagRepository {

    private final JdbcTemplate jdbcTemplate;

    public TelemetryQualityFlagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(TelemetryQualityFlag flag) {
        jdbcTemplate.update("""
                insert into telemetry_quality_flags (id, reading_id, device_id, flag, message, created_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                flag.getId(),
                flag.getReadingId(),
                flag.getDeviceId(),
                flag.getFlag().name(),
                flag.getMessage(),
                Timestamp.from(flag.getCreatedAt()));
    }

    public List<TelemetryQualityFlag> findByDeviceIdOrderByCreatedAtDesc(UUID deviceId) {
        return jdbcTemplate.query("""
                select id, reading_id, device_id, flag, message, created_at
                from telemetry_quality_flags
                where device_id = ?
                order by created_at desc
                limit 500
                """, this::mapRow, deviceId);
    }

    private TelemetryQualityFlag mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TelemetryQualityFlag(
                rs.getObject("id", UUID.class),
                rs.getObject("reading_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                QualityFlag.valueOf(rs.getString("flag")),
                rs.getString("message"),
                toInstant(rs, "created_at"));
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
