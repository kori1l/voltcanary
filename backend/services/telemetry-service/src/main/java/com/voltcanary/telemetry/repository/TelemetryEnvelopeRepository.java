package com.voltcanary.telemetry.repository;

import com.voltcanary.telemetry.dto.TelemetryEnvelopeResponse;
import com.voltcanary.telemetry.dto.TelemetryEnvelopeResponse.Bucket;
import com.voltcanary.telemetry.dto.TelemetryEnvelopeResponse.Summary;
import com.voltcanary.telemetry.dto.TelemetryChartPointResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded display evidence and full-range reducers, without reading all rows into Java. */
@Repository
public class TelemetryEnvelopeRepository {
    private final JdbcTemplate jdbc;
    private static final String SERIES = """
            with raw as (
              select recorded_at, metric_value, quality, source_id,
                     lag(recorded_at) over(order by recorded_at,source_id) previous_at
              from telemetry.measurements where device_id=? and metric_name=?
                and recorded_at>=? and recorded_at<=?
            ), cadence as (
              select greatest(60, coalesce(percentile_cont(0.5) within group
                (order by extract(epoch from recorded_at-previous_at))
                filter(where recorded_at>previous_at)*3, 60)) gap_seconds from raw
            ), series as (
              select raw.*, extract(epoch from recorded_at-previous_at)>cadence.gap_seconds is_gap
              from raw cross join cadence
            )
            """;
    public TelemetryEnvelopeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TelemetryEnvelopeResponse read(UUID deviceId, String metric, Instant from, Instant to, int bucketCount) {
        Object[] params = {deviceId, metric, Timestamp.from(from), Timestamp.from(to)};
        Summary summary = jdbc.queryForObject(SERIES + """
                select count(*) count, min(metric_value) min, max(metric_value) max, avg(metric_value) average,
                  percentile_cont(0.95) within group(order by metric_value) p95,
                  percentile_cont(0.99) within group(order by metric_value) p99,
                  (array_agg(recorded_at order by metric_value, recorded_at, source_id))[1] min_at,
                  (array_agg(recorded_at order by metric_value desc, recorded_at, source_id))[1] max_at,
                  max(recorded_at) last_at,
                  (array_agg(metric_value order by recorded_at desc, source_id desc))[1] last_value,
                  count(*) filter(where is_gap) gap_count from series
                """, (rs, row) -> new Summary(rs.getLong("count"), number(rs, "min"), number(rs, "max"),
                number(rs, "average"), number(rs, "p95"), number(rs, "p99"), time(rs, "min_at"),
                time(rs, "max_at"), time(rs, "last_at"), number(rs, "last_value"), rs.getLong("gap_count")), params);
        long bucketMillis = Math.max(1, (Duration.between(from, to).toMillis() + bucketCount - 1) / bucketCount);
        List<Bucket> buckets = jdbc.query(SERIES + """
                select least(?,floor(extract(epoch from (recorded_at-?))*1000/?)) bucket,
                  count(*) count, min(metric_value) min, max(metric_value) max,
                  (array_agg(recorded_at order by metric_value, recorded_at, source_id))[1] min_at,
                  (array_agg(recorded_at order by metric_value desc, recorded_at, source_id))[1] max_at,
                  min(recorded_at) first_at, (array_agg(metric_value order by recorded_at, source_id))[1] first_value,
                  max(recorded_at) last_at, (array_agg(metric_value order by recorded_at desc, source_id desc))[1] last_value,
                  count(*) filter(where is_gap) gap_count
                from series group by bucket order by bucket
                """, (rs, row) -> {
                    Instant start = from.plusMillis(rs.getLong("bucket") * bucketMillis);
                    Instant end = start.plusMillis(bucketMillis);
                    return new Bucket(start, end.isAfter(to) ? to : end, rs.getLong("count"),
                            rs.getDouble("min"), rs.getDouble("max"), time(rs, "min_at"), time(rs, "max_at"),
                            time(rs, "first_at"), rs.getDouble("first_value"), time(rs, "last_at"),
                            rs.getDouble("last_value"), rs.getLong("gap_count"));
                }, deviceId, metric, Timestamp.from(from), Timestamp.from(to), bucketCount - 1, Timestamp.from(from), bucketMillis);
        Map<String, Long> quality = new LinkedHashMap<>();
        jdbc.query("""
                select quality,count(*) count from telemetry.measurements
                where device_id=? and metric_name=? and recorded_at>=? and recorded_at<=? group by quality order by quality
                """, rs -> { quality.put(rs.getString("quality"), rs.getLong("count")); }, params);
        TreeMap<Instant, Double> points = new TreeMap<>();
        for (Bucket bucket : buckets) {
            points.put(bucket.firstAt(), bucket.firstValue()); points.put(bucket.lastAt(), bucket.lastValue());
            points.put(bucket.minAt(), bucket.min()); points.put(bucket.maxAt(), bucket.max());
        }
        return new TelemetryEnvelopeResponse(deviceId, metric, points.entrySet().stream()
                .map(entry -> new TelemetryChartPointResponse(entry.getKey(), entry.getValue())).toList(),
                summary.count(), "MIN_MAX_ENVELOPE", buckets, summary, quality);
    }
    private static Instant time(ResultSet rs, String key) throws SQLException {
        Timestamp value = rs.getTimestamp(key); return value == null ? null : value.toInstant();
    }
    private static Double number(ResultSet rs, String key) throws SQLException {
        double value = rs.getDouble(key); return rs.wasNull() ? null : value;
    }
}
