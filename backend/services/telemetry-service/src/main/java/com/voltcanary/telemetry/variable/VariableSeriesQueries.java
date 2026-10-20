package com.voltcanary.telemetry.variable;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VariableSeriesQueries {
    public record Descriptor(UUID variableId, UUID dataSourceId, String sourceId, String key, String label,
            String origin, String unit, int revision, long generation, boolean enabled, String metricToken) { }
    public record Point(Instant timestamp, double value, int revision, long generation, long count, double minimum, double maximum) { }
    public record Series(UUID deviceId, UUID variableId, UUID dataSourceId, String metric, String unit,
            List<Point> points, boolean hasMore, Instant nextCursor, String aggregationMode) { }
    private final JdbcTemplate jdbc;

    public VariableSeriesQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Descriptor> descriptors(UUID device) {
        return jdbc.query("select * from variable_series_catalog where device_id=? order by label,data_source_id,variable_id",
                (row, index) -> new Descriptor(row.getObject("variable_id", UUID.class),
                row.getObject("data_source_id", UUID.class), row.getString("external_source_id"),
                row.getString("variable_key"), row.getString("label"), row.getString("origin"),
                row.getString("unit"), row.getInt("revision"), row.getLong("generation"),
                row.getBoolean("enabled"), row.getString("metric_token")), device);
    }

    public Descriptor descriptor(UUID device, UUID source, UUID variable) {
        return descriptors(device).stream().filter(item -> item.dataSourceId().equals(source)
                && item.variableId().equals(variable)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Variable series not found"));
    }

    public Series read(UUID device, UUID source, UUID variable, Instant from, Instant to,
            int limit, Instant cursor, long bucketSeconds, String reducer) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || limit < 1 || limit > 2000 || bucketSeconds < 0
                || !List.of("AVG", "MIN", "MAX").contains(reducer)
                || bucketSeconds > 0 && (bucketSeconds > Duration.ofDays(31).toSeconds()
                    || Duration.between(from, to).toSeconds() / bucketSeconds > 2000)
                || cursor != null && (cursor.isBefore(from) || cursor.isAfter(to))) {
            throw new IllegalArgumentException("Invalid variable range, resolution or cursor");
        }
        var descriptor = descriptor(device, source, variable);
        String join = """
                from variable_measurement_lineage l join telemetry.measurements m
                on m.device_id=l.device_id and m.source_id=l.external_source_id
                and m.recorded_at=l.recorded_at and m.metric_name=l.metric_token
                where l.device_id=? and l.data_source_id=? and l.variable_id=?
                and l.status='OK' and l.recorded_at>=? and l.recorded_at<?
                """;
        var arguments = new java.util.ArrayList<Object>(List.of(device, source, variable,
                Timestamp.from(from), Timestamp.from(to)));
        if (cursor != null) { join += " and l.recorded_at>?"; arguments.add(Timestamp.from(cursor)); }
        String sql;
        if (bucketSeconds == 0) {
            sql = "select m.recorded_at,m.metric_value,l.revision,l.bundle_generation,1 sample_count,m.metric_value minimum,m.metric_value maximum "
                    + join + " order by m.recorded_at,l.revision limit ?";
        } else {
            sql = "select min(m.recorded_at) recorded_at," + reducer + "(m.metric_value) metric_value,l.revision,l.bundle_generation,"
                    + "count(*) sample_count,min(m.metric_value) minimum,max(m.metric_value) maximum "
                    + join + " group by floor(extract(epoch from m.recorded_at)/" + bucketSeconds
                    + "),l.revision,l.bundle_generation order by min(m.recorded_at),l.revision limit ?";
        }
        arguments.add(limit + 1);
        List<Point> fetched = jdbc.query(sql, (row, index) -> new Point(row.getTimestamp("recorded_at").toInstant(),
                row.getDouble("metric_value"), row.getInt("revision"), row.getLong("bundle_generation"), row.getLong("sample_count"),
                row.getDouble("minimum"), row.getDouble("maximum")), arguments.toArray());
        boolean more = fetched.size() > limit;
        List<Point> points = more ? fetched.subList(0, limit) : fetched;
        return new Series(device, variable, source, descriptor.metricToken(), descriptor.unit(), points,
                more, more ? points.getLast().timestamp() : null,
                bucketSeconds == 0 ? "RAW" : reducer + "_BY_REVISION_UNFILLED");
    }

    public Series latest(UUID device, UUID source, UUID variable) {
        var descriptor = descriptor(device, source, variable);
        List<Point> points = jdbc.query("""
                select m.recorded_at,m.metric_value,l.revision,l.bundle_generation from variable_measurement_lineage l
                join telemetry.measurements m on m.device_id=l.device_id and m.source_id=l.external_source_id
                and m.recorded_at=l.recorded_at and m.metric_name=l.metric_token
                where l.device_id=? and l.data_source_id=? and l.variable_id=? and l.status='OK'
                order by l.recorded_at desc,l.revision desc,l.id desc limit 1
                """, (row, index) -> new Point(row.getTimestamp("recorded_at").toInstant(), row.getDouble("metric_value"),
                row.getInt("revision"), row.getLong("bundle_generation"), 1, row.getDouble("metric_value"), row.getDouble("metric_value")),
                device, source, variable);
        return new Series(device, variable, source, descriptor.metricToken(), descriptor.unit(), points, false, null, "LATEST");
    }

    public List<java.util.Map<String, Object>> outcomes(UUID device, UUID source) {
        return jdbc.query("""
                select variable_id,revision,bundle_generation,status,recorded_at from variable_measurement_lineage
                where device_id=? and data_source_id=? order by recorded_at desc,id desc limit 100
                """, (row, index) -> java.util.Map.<String, Object>of(
                "variableId", row.getObject("variable_id", UUID.class), "revision", row.getInt("revision"),
                "generation", row.getLong("bundle_generation"), "status", row.getString("status"),
                "recordedAt", row.getTimestamp("recorded_at").toInstant()), device, source);
    }
}
