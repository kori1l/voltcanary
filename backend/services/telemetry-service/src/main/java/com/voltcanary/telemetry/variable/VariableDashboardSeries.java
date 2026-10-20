package com.voltcanary.telemetry.variable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class VariableDashboardSeries {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final VariableSeriesQueries queries;

    public VariableDashboardSeries(JdbcTemplate jdbc, ObjectMapper mapper, VariableSeriesQueries queries) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.queries = queries;
    }

    public List<ObjectNode> read(DashboardOwnerReadRequest request, DashboardOwnerReadRequest.Series selection) {
        if (selection.variableId() == null || selection.dataSourceId() == null) throw new IllegalArgumentException("Explicit variable source required");
        var descriptor = queries.descriptor(selection.deviceId(), selection.dataSourceId(), selection.variableId());
        if (!descriptor.metricToken().equals(selection.metric())) throw new IllegalArgumentException("Variable metric identity mismatch");
        var grouped = new LinkedHashMap<String, ObjectNode>();
        long seconds = Math.max(1, Duration.between(request.time().from(), request.time().to()).getSeconds());
        var bins = jdbc.queryForList("""
                WITH raw AS (
                  SELECT m.recorded_at,m.metric_value,m.unit,l.revision,l.bundle_generation,
                    lag(m.recorded_at) OVER(PARTITION BY l.revision,l.bundle_generation ORDER BY m.recorded_at) previous_at
                  FROM telemetry.measurements m JOIN variable_measurement_lineage l
                    ON m.device_id=l.device_id AND m.source_id=l.external_source_id
                    AND m.recorded_at=l.recorded_at AND m.metric_name=l.metric_token
                  WHERE l.device_id=? AND l.data_source_id=? AND l.variable_id=? AND l.account_id=?
                    AND l.status='OK' AND m.recorded_at>=? AND m.recorded_at<?
                ), bins AS (
                  SELECT *,least(199,floor(extract(epoch FROM recorded_at-?)/?*200)) bucket
                  FROM raw
                )
                SELECT bucket,revision,bundle_generation,unit,count(*) sample_count,min(metric_value) minimum,max(metric_value) maximum,
                  avg(metric_value) mean,min(recorded_at) first_at,max(recorded_at) last_at,
                  (array_agg(metric_value ORDER BY recorded_at))[1] first_value,
                  (array_agg(metric_value ORDER BY recorded_at DESC))[1] last_value,
                  (array_agg(recorded_at ORDER BY metric_value,recorded_at))[1] minimum_at,
                  (array_agg(recorded_at ORDER BY metric_value DESC,recorded_at))[1] maximum_at,
                  count(*) FILTER(WHERE extract(epoch FROM recorded_at-previous_at)>900) gap_count
                FROM bins GROUP BY bucket,revision,bundle_generation,unit ORDER BY bucket,revision,bundle_generation LIMIT 201
                """, selection.deviceId(), selection.dataSourceId(), selection.variableId(), request.accountId(),
                Timestamp.from(request.time().from()), Timestamp.from(request.time().to()), Timestamp.from(request.time().from()), seconds);
        if (bins.size() > 200) throw new IllegalArgumentException("Variable graph exceeds revision/bucket budget; narrow the range");
        for (var bin : bins) {
            int revision = ((Number) bin.get("revision")).intValue();
            long generation = ((Number) bin.get("bundle_generation")).longValue();
            ObjectNode series = grouped.computeIfAbsent(revision + ":" + generation, version -> {
                var result = mapper.createObjectNode().put("deviceId", selection.deviceId().toString())
                        .put("metric", selection.metric()).put("variableId", selection.variableId().toString())
                        .put("dataSourceId", selection.dataSourceId().toString()).put("revision", revision).put("generation", generation)
                        .put("label", descriptor.label() + " · v" + revision).put("mode", "MIN_MAX_ENVELOPE")
                        .put("displayBucketLimit", 200).put("gapPolicy", "UNFILLED_GAPS_OVER_900_SECONDS");
                result.putArray("buckets");
                return result;
            });
            var bucket = series.withArray("buckets").addObject().put("index", ((Number) bin.get("bucket")).intValue())
                    .put("unit", bin.get("unit").toString());
            for (String key : List.of("sample_count", "minimum", "maximum", "mean", "first_value", "last_value", "gap_count")) {
                String target = "sample_count".equals(key) ? "sampleCount" : "gap_count".equals(key) ? "gapCount" : key;
                bucket.put(target, ((Number) bin.get(key)).doubleValue());
            }
            for (var key : java.util.Map.of("first_at", "firstAt", "last_at", "lastAt",
                    "minimum_at", "minimumAt", "maximum_at", "maximumAt").entrySet()) {
                Object value = bin.get(key.getKey());
                java.time.Instant time = value instanceof Timestamp timestamp ? timestamp.toInstant()
                        : ((java.time.OffsetDateTime) value).toInstant();
                bucket.put(key.getValue(), time.toString());
            }
        }
        return List.copyOf(grouped.values());
    }

    public ObjectNode metadata(DashboardOwnerReadRequest.Series selection) {
        var descriptor = queries.descriptor(selection.deviceId(), selection.dataSourceId(), selection.variableId());
        if (!descriptor.metricToken().equals(selection.metric())) throw new IllegalArgumentException("Variable metric identity mismatch");
        return mapper.createObjectNode().put("key", descriptor.metricToken()).put("unit", descriptor.unit())
                .put("label", descriptor.label()).put("variableId", descriptor.variableId().toString())
                .put("dataSourceId", descriptor.dataSourceId().toString());
    }
}
