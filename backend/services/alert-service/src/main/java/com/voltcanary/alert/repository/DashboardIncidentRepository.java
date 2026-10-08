package com.voltcanary.alert.repository;

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

/** Incidents are alerts, not notification attempts. Counts are never page lengths. */
@Repository
public class DashboardIncidentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DashboardIncidentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode read(DashboardOwnerReadRequest request, List<UUID> allowed) {
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        ObjectNode result = mapper.createObjectNode().put("requestedCount", request.deviceIds().size())
                .put("evaluatedCount", allowed.size()).put("deniedBindingCount", request.deviceIds().size() - allowed.size())
                .put("queryAt", Instant.now().toString()).put("snapshotCapturedAt", Instant.now().toString());
        result.set("authorizedDeviceIds", mapper.valueToTree(allowed));
        ArrayNode rows = result.putArray("rows");
        ArrayNode byDevice = result.putArray("byDevice");
        if (allowed.isEmpty()) {
            result.put("activeNow", 0).put("criticalOpenNow", 0).put("createdInInterval", 0).put("total", 0).put("hasNext", false);
            return result;
        }
        String base = " FROM alerts WHERE device_id IN (" + placeholders(allowed.size()) + ") AND (account_id=? OR account_id IS NULL)";
        jdbc.query("""
                SELECT device_id,count(*) FILTER(WHERE status IN ('OPEN','ACKNOWLEDGED') AND created_at<=?) active_now,
                count(*) FILTER(WHERE status IN ('OPEN','ACKNOWLEDGED') AND severity='CRITICAL' AND created_at<=?) critical_now,
                count(*) FILTER(WHERE created_at>=? AND created_at<?) created_in_interval
                """ + base + " GROUP BY device_id ORDER BY device_id", row -> { byDevice.addObject()
                .put("deviceId", row.getString("device_id")).put("activeNow", row.getLong("active_now"))
                .put("criticalOpenNow", row.getLong("critical_now")).put("createdInInterval", row.getLong("created_in_interval")); },
                countArgs(request, allowed).toArray());
        long active = 0;
        long critical = 0;
        long created = 0;
        for (var device : byDevice) {
            active += device.path("activeNow").asLong();
            critical += device.path("criticalOpenNow").asLong();
            created += device.path("createdInInterval").asLong();
        }
        result.put("activeNow", active).put("criticalOpenNow", critical).put("createdInInterval", created);
        boolean interval = "CREATED".equals(request.incidentMode());
        String predicate = interval ? " AND created_at>=? AND created_at<?" : " AND status IN ('OPEN','ACKNOWLEDGED') AND created_at<=?";
        List<Object> rowArgs = new ArrayList<>(allowed);
        rowArgs.add(request.accountId());
        rowArgs.add(Timestamp.from(interval ? request.time().from() : request.time().asOf()));
        if (interval) rowArgs.add(Timestamp.from(request.time().to()));
        rowArgs.add(request.rows());
        rowArgs.add(request.offset());
        jdbc.query("SELECT id,device_id,title,status,severity,created_at,resolved_at" + base + predicate
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", row -> {
            ObjectNode incident = rows.addObject().put("id", row.getString("id")).put("deviceId", row.getString("device_id"))
                    .put("title", row.getString("title")).put("status", row.getString("status")).put("severity", row.getString("severity"));
            time(incident, row, "created_at", "createdAt");
            time(incident, row, "resolved_at", "resolvedAt");
            incident.putArray("actions");
        }, rowArgs.toArray());
        long total = interval ? created : active;
        result.put("total", total).put("hasNext", request.offset() + rows.size() < total)
                .put("listTimeBasis", interval ? "CREATED_IN_HALF_OPEN_INTERVAL" : "CURRENT_STATUS_AT_OWNER_READ");
        return result;
    }

    private List<Object> countArgs(DashboardOwnerReadRequest request, List<UUID> allowed) {
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(request.time().asOf()), Timestamp.from(request.time().asOf()),
                Timestamp.from(request.time().from()), Timestamp.from(request.time().to())));
        args.addAll(allowed);
        args.add(request.accountId());
        return args;
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
