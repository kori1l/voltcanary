package com.voltcanary.telemetry.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import com.voltcanary.telemetry.service.MetricCatalog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Full qualified reducers and bounded display bins, all within the telemetry owner. */
@Repository
public class DashboardTelemetryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MetricCatalog catalog;

    public DashboardTelemetryRepository(JdbcTemplate jdbc, ObjectMapper mapper, MetricCatalog catalog) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode read(DashboardOwnerReadRequest request, List<UUID> allowed) {
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        long deadline = System.nanoTime() + 3_000_000_000L;
        ObjectNode result = mapper.createObjectNode();
        result.put("requestedCount", request.deviceIds().size()).put("evaluatedCount", allowed.size())
                .put("deniedBindingCount", request.deviceIds().size() - allowed.size())
                .put("queryAt", Instant.now().toString()).put("asOf", request.time().asOf().toString())
                .put("intervalBasis", "HALF_OPEN_UTC_RECORDED_AT").put("weighting", "ONE_ACCEPTED_SAMPLE_ONE_WEIGHT")
                .put("maintenancePolicy", "RAW_MEASUREMENTS_NOT_EXCLUDED")
                .put("gapPolicy", "INTERIOR_GAPS_OBSERVED_MEDIAN_WITH_900_SECOND_DEFAULT");
        result.set("authorizedDeviceIds", mapper.valueToTree(allowed));
        ArrayNode registered = result.putArray("registeredMetrics");
        catalog.all().forEach(entry -> registered.addObject().put("key", entry.getKey()).put("unit", entry.getValue().unit()));
        if (allowed.isEmpty()) {
            result.putArray("latest");
            result.putArray("summaries");
            result.putArray("baselineSummaries");
            result.putArray("series");
            result.putObject("measurementQualityCounts");
            result.putObject("validationFlagCounts");
            return result;
        }
        latest(result.putArray("latest"), request, allowed);
        summaries(result.putArray("summaries"), request, allowed, request.time().from(), request.time().to());
        if (request.time().compareFrom() != null) {
            summaries(result.putArray("baselineSummaries"), request, allowed, request.time().compareFrom(), request.time().compareTo());
        } else {
            result.putArray("baselineSummaries");
        }
        quality(result, request, allowed);
        ArrayNode series = result.putArray("series");
        for (DashboardOwnerReadRequest.Series selection : request.series()) {
            if (System.nanoTime() >= deadline) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telemetry display read exceeded its budget");
            }
            if (allowed.contains(selection.deviceId())) {
                series.add(series(request, selection));
            }
        }
        return result;
    }

    private void latest(ArrayNode target, DashboardOwnerReadRequest request, List<UUID> ids) {
        List<Object> args = new ArrayList<>(ids);
        args.add(request.accountId());
        args.add(Timestamp.from(request.time().asOf()));
        jdbc.query("""
                SELECT DISTINCT ON(device_id,metric_name) device_id,metric_name,unit,metric_value,source_id,
                recorded_at,received_at,quality FROM telemetry.measurements WHERE device_id IN (
                """ + placeholders(ids.size()) + """
                ) AND (account_id=? OR account_id IS NULL) AND recorded_at<=? AND metric_name<>'_legacy'
                AND metric_value BETWEEN -1e308 AND 1e308
                ORDER BY device_id,metric_name,recorded_at DESC,source_id DESC
                """, row -> {
            ObjectNode value = target.addObject().put("deviceId", row.getString("device_id"))
                    .put("metric", row.getString("metric_name")).put("unit", row.getString("unit"))
                    .put("value", row.getDouble("metric_value")).put("sourceId", row.getString("source_id"))
                    .put("quality", row.getString("quality"));
            time(value, row, "recorded_at", "measuredAt");
            time(value, row, "received_at", "receivedAt");
        }, args.toArray());
    }

    private void summaries(ArrayNode target, DashboardOwnerReadRequest request, List<UUID> ids, Instant from, Instant to) {
        if (request.metrics().isEmpty()) {
            return;
        }
        List<Object> args = new ArrayList<>(ids);
        args.add(request.accountId());
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        args.addAll(request.metrics());
        jdbc.query("""
                SELECT metric_name,unit,count(*) sample_count,count(DISTINCT device_id) device_count,
                min(metric_value) minimum,max(metric_value) maximum,avg(metric_value) mean,
                percentile_cont(0.95) WITHIN GROUP(ORDER BY metric_value) p95,
                (array_agg(recorded_at ORDER BY metric_value,recorded_at,source_id))[1] minimum_at,
                (array_agg(recorded_at ORDER BY metric_value DESC,recorded_at,source_id))[1] maximum_at,
                max(recorded_at) measured_at,max(received_at) received_at
                FROM telemetry.measurements WHERE device_id IN (
                """ + placeholders(ids.size()) + """
                ) AND (account_id=? OR account_id IS NULL) AND recorded_at>=? AND recorded_at<?
                AND metric_name IN (
                """ + placeholders(request.metrics().size()) + """
                ) AND metric_value BETWEEN -1e308 AND 1e308 GROUP BY metric_name,unit ORDER BY metric_name,unit
                """, row -> {
            ObjectNode summary = target.addObject().put("metric", row.getString("metric_name")).put("unit", row.getString("unit"))
                    .put("sampleCount", row.getLong("sample_count")).put("deviceCount", row.getLong("device_count"));
            for (String column : List.of("minimum", "maximum", "mean", "p95")) {
                number(summary, row, column);
            }
            time(summary, row, "minimum_at", "minimumAt");
            time(summary, row, "maximum_at", "maximumAt");
            time(summary, row, "measured_at", "measuredAt");
            time(summary, row, "received_at", "receivedAt");
        }, args.toArray());
    }

    private void quality(ObjectNode result, DashboardOwnerReadRequest request, List<UUID> ids) {
        List<Object> args = new ArrayList<>(ids);
        args.add(request.accountId());
        args.add(Timestamp.from(request.time().from()));
        args.add(Timestamp.from(request.time().to()));
        ObjectNode measurements = result.putObject("measurementQualityCounts");
        jdbc.query("SELECT quality,count(*) count FROM telemetry.measurements WHERE device_id IN ("
                + placeholders(ids.size()) + ") AND (account_id=? OR account_id IS NULL) AND recorded_at>=? AND recorded_at<? AND metric_name<>'_legacy' GROUP BY quality",
                row -> { measurements.put(row.getString("quality") == null ? "UNSPECIFIED" : row.getString("quality"), row.getLong("count")); }, args.toArray());
        ObjectNode flags = result.putObject("validationFlagCounts");
        jdbc.query("SELECT flag,count(*) count FROM telemetry_quality_flags WHERE device_id IN ("
                + placeholders(ids.size()) + ") AND (account_id=? OR account_id IS NULL) AND created_at>=? AND created_at<? GROUP BY flag",
                row -> { flags.put(row.getString("flag"), row.getLong("count")); }, args.toArray());
        result.put("validationFlagTimeBasis", "RECEIPT_CREATED_AT");
    }

    private ObjectNode series(DashboardOwnerReadRequest request, DashboardOwnerReadRequest.Series selection) {
        Instant from = request.time().from();
        Instant to = request.time().to();
        ObjectNode result = mapper.createObjectNode().put("deviceId", selection.deviceId().toString())
                .put("metric", selection.metric()).put("mode", "MIN_MAX_ENVELOPE").put("displayBucketLimit", 200);
        ArrayNode buckets = result.putArray("buckets");
        jdbc.query("""
                WITH raw AS (
                  SELECT recorded_at,metric_value,unit,source_id,
                  lag(recorded_at) OVER(PARTITION BY source_id ORDER BY recorded_at) previous_at
                  FROM telemetry.measurements WHERE device_id=? AND metric_name=? AND (account_id=? OR account_id IS NULL)
                  AND recorded_at>=? AND recorded_at<? AND metric_value BETWEEN -1e308 AND 1e308
                ), cadence AS (
                  SELECT CASE WHEN count(*) FILTER(WHERE recorded_at>previous_at)>=3 THEN greatest(60,
                  percentile_cont(0.5) WITHIN GROUP(ORDER BY extract(epoch FROM recorded_at-previous_at))
                  FILTER(WHERE recorded_at>previous_at)*3) ELSE 900 END seconds FROM raw
                ), bins AS (
                  SELECT raw.*,least(199,floor(extract(epoch FROM recorded_at-?)/?*200)) bucket,
                  extract(epoch FROM recorded_at-previous_at)>cadence.seconds is_gap FROM raw CROSS JOIN cadence
                ) SELECT bucket,unit,count(*) sample_count,min(metric_value) minimum,max(metric_value) maximum,avg(metric_value) mean,
                min(recorded_at) first_at,max(recorded_at) last_at,
                (array_agg(metric_value ORDER BY recorded_at,source_id))[1] first_value,
                (array_agg(metric_value ORDER BY recorded_at DESC,source_id DESC))[1] last_value,
                (array_agg(recorded_at ORDER BY metric_value,recorded_at,source_id))[1] minimum_at,
                (array_agg(recorded_at ORDER BY metric_value DESC,recorded_at,source_id))[1] maximum_at,
                count(*) FILTER(WHERE is_gap) gap_count FROM bins GROUP BY bucket,unit ORDER BY bucket,unit
                """, row -> {
            ObjectNode bucket = buckets.addObject().put("index", row.getInt("bucket")).put("unit", row.getString("unit"))
                    .put("sampleCount", row.getLong("sample_count")).put("gapCount", row.getLong("gap_count"));
            for (String column : List.of("minimum", "maximum", "mean", "first_value", "last_value")) {
                number(bucket, row, column);
            }
            time(bucket, row, "first_at", "firstAt");
            time(bucket, row, "last_at", "lastAt");
            time(bucket, row, "minimum_at", "minimumAt");
            time(bucket, row, "maximum_at", "maximumAt");
        }, selection.deviceId(), selection.metric(), request.accountId(), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), java.time.Duration.between(from, to).toNanos() / 1_000_000_000.0);
        return result;
    }

    private void number(ObjectNode node, ResultSet row, String column) throws SQLException {
        double value = row.getDouble(column);
        if (row.wasNull() || !Double.isFinite(value)) node.putNull(column);
        else node.put(column, value);
    }

    private void time(ObjectNode node, ResultSet row, String column, String field) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) node.putNull(field);
        else node.put(field, value.toInstant().toString());
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
