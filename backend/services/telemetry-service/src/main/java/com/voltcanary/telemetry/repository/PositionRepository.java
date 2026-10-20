package com.voltcanary.telemetry.repository;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.common.events.telemetry.TransportContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.voltcanary.telemetry.dto.PositionEvidence.Point;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PositionRepository {
    private final JdbcTemplate jdbc;
    private final boolean postgres;
    private final ObjectMapper mapper;

    public PositionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    }

    public void insert(TelemetryReceivedEvent event, UUID readingId, List<String> quality) {
        String sql = """
                insert into telemetry.positions
                (reading_id,event_id,device_id,source_id,recorded_at,received_at,latitude,longitude,speed_kmh,heading_deg,quality,ignition,fuel_percent,odometer_km,transport_context,configured_fields)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        if (postgres) {
            sql += " on conflict (device_id,source_id,recorded_at) do nothing";
        } else if (jdbc.queryForObject("select count(*) from telemetry.positions where device_id=? and source_id=? and recorded_at=?",
                Long.class, event.deviceId(), event.sourceId(), Timestamp.from(event.timestamp())) > 0) {
            return;
        }
        jdbc.update(sql, readingId, event.eventId(), event.deviceId(), event.sourceId(), Timestamp.from(event.timestamp()),
                Timestamp.from(event.receivedAt() == null ? Instant.now() : event.receivedAt()),
                value(event, "latitude"), value(event, "longitude"), value(event, "speedKmh"), value(event, "headingDeg"),
                quality.isEmpty() ? "OK" : String.join(",", quality), value(event, "ignition"), value(event, "fuelPercent"), value(event, "odometerKm"), encodeContext(event.metrics()), encodeFields(event.metrics()));
    }

    public Point latest(UUID device, String source, Instant asOf) {
        return jdbc.query("select * from telemetry.positions where device_id=? and source_id=? and recorded_at<=? order by recorded_at desc limit 1",
                this::point, device, source, Timestamp.from(asOf)).stream().findFirst().orElse(null);
    }

    public List<Point> history(UUID device, String source, Instant from, Instant to) {
        return jdbc.query("select * from telemetry.positions where device_id=? and source_id=? and recorded_at>? and recorded_at<=? order by recorded_at desc limit 1001",
                this::point, device, source, Timestamp.from(from), Timestamp.from(to));
    }

    public List<Point> recordings(UUID device, String source, Instant after, Instant to, int limit) {
        return jdbc.query("select * from telemetry.positions where device_id=? and source_id=? and recorded_at>? and recorded_at<=? order by recorded_at asc limit ?",
                this::point, device, source, Timestamp.from(after), Timestamp.from(to), limit);
    }

    private Point point(ResultSet row, int index) throws SQLException {
        return new Point(row.getTimestamp("recorded_at").toInstant(), row.getTimestamp("received_at").toInstant(),
                row.getDouble("latitude"), row.getDouble("longitude"), (Double) row.getObject("speed_kmh"),
                (Double) row.getObject("heading_deg"), Arrays.asList(row.getString("quality").split(",")),
                (Double) row.getObject("ignition"), (Double) row.getObject("fuel_percent"), (Double) row.getObject("odometer_km"), decodeContext(row.getString("transport_context")), decodeFields(row.getString("configured_fields")));
    }

    private String encodeFields(java.util.Map<String,Object> metrics) {
        try { return mapper.writeValueAsString(com.voltcanary.common.events.telemetry.ConfiguredFields.values(metrics)); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid configured fields", failure); }
    }
    private java.util.Map<String,java.util.Map<String,Object>> decodeFields(String json) throws SQLException {
        if (json == null) return java.util.Map.of();
        try { return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,java.util.Map<String,Object>>>() { }); }
        catch (JsonProcessingException failure) { throw new SQLException("Invalid stored fields", failure); }
    }

    private String encodeContext(java.util.Map<String, Object> metrics) {
        var context = TransportContext.fromMetrics(metrics);
        if (context == null) return null;
        try { return mapper.writeValueAsString(context); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid transport context", invalid); }
    }

    private TransportContext decodeContext(String json) throws SQLException {
        if (json == null) return null;
        try { return mapper.readValue(json, TransportContext.class); }
        catch (JsonProcessingException invalid) { throw new SQLException("Invalid stored transport context", invalid); }
    }

    private Double value(TelemetryReceivedEvent event, String metric) {
        return event.metrics().get(metric) instanceof Number number ? number.doubleValue() : null;
    }
}
