package com.voltcanary.analytics.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads persisted analytics evidence only; no recalculation or ML invocation. */
@Repository
public class DashboardAnalyticsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DashboardAnalyticsRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode read(DashboardOwnerReadRequest request, List<UUID> allowed) {
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        ObjectNode result = mapper.createObjectNode().put("requestedCount", request.deviceIds().size())
                .put("evaluatedCount", allowed.size()).put("deniedBindingCount", request.deviceIds().size() - allowed.size())
                .put("queryAt", Instant.now().toString()).put("source", "STORED_ANALYTICS_RECORDS")
                .put("confidenceMeaning", "MODEL_REPORTED_CONFIDENCE_NOT_ACCURACY")
                .put("riskMeaning", "RISK_SCORE_NOT_FAILURE_PROBABILITY");
        result.set("authorizedDeviceIds", mapper.valueToTree(allowed));
        ArrayNode risks = result.putArray("risks");
        ArrayNode predictions = result.putArray("predictions");
        ArrayNode exclusions = result.putArray("maintenanceIntervals");
        if (allowed.isEmpty()) return result;
        List<Object> args = new ArrayList<>(allowed);
        args.add(request.accountId());
        args.add(Timestamp.from(request.time().asOf()));
        jdbc.query("""
                SELECT DISTINCT ON(device_id) device_id,score,level,window_start,window_end,calculated_at
                FROM risk_scores WHERE device_id IN (
                """ + placeholders(allowed.size()) + """
                ) AND (account_id=? OR account_id IS NULL) AND calculated_at<=?
                ORDER BY device_id,calculated_at DESC,id DESC
                """, row -> {
            ObjectNode risk = risks.addObject().put("deviceId", row.getString("device_id")).put("level", row.getString("level"));
            number(risk, row, "score");
            time(risk, row, "window_start", "windowFrom");
            time(risk, row, "window_end", "windowTo");
            time(risk, row, "calculated_at", "measuredAt");
        }, args.toArray());
        List<Object> predictionArgs = new ArrayList<>(allowed);
        predictionArgs.add(request.accountId());
        predictionArgs.add(Timestamp.from(request.time().asOf()));
        jdbc.query("""
                SELECT DISTINCT ON(device_id) device_id,model_name,model_version,confidence,status,policy_source,
                fallback_reason,prediction_json,created_at FROM prediction_results WHERE device_id IN (
                """ + placeholders(allowed.size()) + """
                ) AND (account_id=? OR account_id IS NULL) AND shadow_prediction=false AND created_at<=? ORDER BY device_id,created_at DESC,id DESC
                """, row -> {
            ObjectNode prediction = predictions.addObject().put("deviceId", row.getString("device_id"))
                    .put("modelName", row.getString("model_name")).put("modelVersion", row.getString("model_version"))
                    .put("predictionStatus", row.getString("status")).put("policySource", row.getString("policy_source"))
                    .put("fallbackReason", row.getString("fallback_reason"));
            number(prediction, row, "confidence");
            time(prediction, row, "created_at", "measuredAt");
            metadata(prediction, row.getString("prediction_json"));
        }, predictionArgs.toArray());
        List<Object> exclusionArgs = new ArrayList<>(allowed);
        exclusionArgs.add(request.accountId());
        exclusionArgs.add(Timestamp.from(request.time().to()));
        exclusionArgs.add(Timestamp.from(request.time().from()));
        jdbc.query("""
                SELECT maintenance_window_id,device_id,start_at,end_at,exclude_from_analytics,exclude_from_ml
                FROM maintenance_windows_read_model WHERE device_id IN (
                """ + placeholders(allowed.size()) + """
                ) AND account_id=? AND cancelled_at IS NULL AND start_at<? AND end_at>? ORDER BY start_at,maintenance_window_id
                """, row -> {
            ObjectNode interval = exclusions.addObject().put("id", row.getString("maintenance_window_id"))
                    .put("deviceId", row.getString("device_id")).put("excludeFromAnalytics", row.getBoolean("exclude_from_analytics"))
                    .put("excludeFromMl", row.getBoolean("exclude_from_ml"));
            time(interval, row, "start_at", "from");
            time(interval, row, "end_at", "to");
        }, exclusionArgs.toArray());
        return result;
    }

    private void metadata(ObjectNode target, String json) {
        try {
            JsonNode source = mapper.readTree(json);
            for (String key : List.of("modelFamily", "horizonDays", "predictionPath", "predictionStatus",
                    "modelStatus", "failureRiskScore", "datasetVersionId", "featureSnapshotId", "reason")) {
                String snake = key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
                JsonNode value = source.has(key) ? source.get(key) : source.get(snake);
                if (value != null && value.isValueNode()) target.set(key, value);
            }
        } catch (Exception ignored) {
            target.put("payloadStatus", "INVALID_STORED_METADATA");
        }
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
