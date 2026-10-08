package com.voltcanary.device.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.common.web.DashboardOwnerReadRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DashboardMaintenanceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DashboardMaintenanceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode read(DashboardOwnerReadRequest request, List<UUID> allowed) {
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        ObjectNode result = mapper.createObjectNode().put("requestedCount", request.deviceIds().size())
                .put("evaluatedCount", allowed.size()).put("deniedBindingCount", request.deviceIds().size() - allowed.size())
                .put("queryAt", Instant.now().toString()).put("timeBasis", "HALF_OPEN_WINDOW_OVERLAP");
        result.set("authorizedDeviceIds", mapper.valueToTree(allowed));
        ArrayNode rows = result.putArray("rows");
        if (allowed.isEmpty()) {
            return result.put("total", 0).put("hasNext", false);
        }
        String where = """
                 WHERE o.account_id=? AND o.cancelled_at IS NULL AND o.start_at<? AND o.end_at>?
                 AND EXISTS(SELECT 1 FROM maintenance_windows w WHERE w.operation_id=o.id
                 AND w.account_id=o.account_id AND w.device_id IN (
                """ + placeholders(allowed.size()) + "))";
        List<Object> args = new ArrayList<>(List.of(request.accountId(), Timestamp.from(request.time().to()),
                Timestamp.from(request.time().from())));
        args.addAll(allowed);
        long count = jdbc.queryForObject("SELECT count(*) FROM maintenance_operations o" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(List.of(Timestamp.from(request.time().asOf()), Timestamp.from(request.time().asOf())));
        pageArgs.addAll(args);
        pageArgs.add(request.rows());
        pageArgs.add(request.offset());
        jdbc.query("""
                SELECT o.id,o.title,o.start_at,o.end_at,o.suppress_alerts,o.exclude_from_analytics,o.exclude_from_ml,
                CASE WHEN o.end_at<=? THEN 'COMPLETED' WHEN o.start_at<=? THEN 'ACTIVE' ELSE 'SCHEDULED' END status
                FROM maintenance_operations o
                """ + where + " ORDER BY o.start_at,o.id LIMIT ? OFFSET ?", row -> {
            ObjectNode operation = rows.addObject().put("id", row.getString("id")).put("title", row.getString("title"))
                    .put("status", row.getString("status")).put("startAt", row.getTimestamp("start_at").toInstant().toString())
                    .put("endAt", row.getTimestamp("end_at").toInstant().toString())
                    .put("suppressAlerts", row.getBoolean("suppress_alerts"))
                    .put("excludeFromAnalytics", row.getBoolean("exclude_from_analytics"))
                    .put("excludeFromMl", row.getBoolean("exclude_from_ml"));
            ArrayNode devices = operation.putArray("deviceIds");
            List<Object> deviceArgs = new ArrayList<>(List.of(UUID.fromString(row.getString("id"))));
            deviceArgs.addAll(allowed);
            jdbc.query("SELECT device_id FROM maintenance_windows WHERE operation_id=? AND device_id IN ("
                    + placeholders(allowed.size()) + ") ORDER BY device_id", device -> { devices.add(device.getString("device_id")); }, deviceArgs.toArray());
        }, pageArgs.toArray());
        return result.put("total", count).put("hasNext", request.offset() + rows.size() < count);
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
