package com.voltcanary.analytics.service;

import com.voltcanary.common.web.JdbcDeviceFence;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceHistoryDeletion {
    private final JdbcTemplate jdbc;
    private final JdbcDeviceFence fence;

    public DeviceHistoryDeletion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        fence = new JdbcDeviceFence(jdbc);
    }

    @Transactional
    public void purgeSources(List<UUID> sources) {
        for (UUID source : sources) {
            jdbc.update("delete from variable_rule_evaluations where source_id=?", source);
            jdbc.update("delete from variable_rule_state where source_id=?", source);
        }
    }

    @Transactional
    public boolean writable(UUID device) { return fence.writable(device); }

    @Transactional
    public void close(UUID device, boolean purge) {
        fence.close(device);
        jdbc.update("update analytics_jobs set status='CANCELLED',active_key=null,completed_at=CURRENT_TIMESTAMP,locked_at=null,locked_by=null where device_id=? and status in ('PENDING','RUNNING')", device);
        if (!purge) return;
        if (jdbc.queryForObject("select count(*) from ml_canonical_metric_rows where device_id=?", Long.class, device) > 0
                || jdbc.queryForObject("select count(*) from ml_feature_rows where device_id=?", Long.class, device) > 0
                || jdbc.queryForObject("select count(*) from ml_imported_metric_rows where matched_device_id=?", Long.class, device) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Remove dependent ML datasets or feature snapshots before purging device history");
        }
        for (String table : List.of("device_baselines", "feature_sets", "anomalies", "risk_scores", "prediction_results",
                "recommendations", "analytics_jobs", "maintenance_windows_read_model", "maintenance_outcomes_read_model", "ml_settings")) {
            jdbc.update("delete from " + table + " where device_id=?", device);
        }
        jdbc.update("delete from ml_label_audit where label_id in (select id from ml_labels where device_id=?)", device);
        jdbc.update("delete from ml_labels where device_id=?", device);
        jdbc.update("delete from ml_deployment_audit_events where policy_id in (select id from ml_deployment_policies where device_id=?)", device);
        jdbc.update("delete from ml_deployment_policies where device_id=?", device);
        jdbc.update("delete from ml_external_device_mappings where matched_device_id=?", device);
        jdbc.update("delete from analytics_event_outbox where payload_json like ?", "%" + device + "%");
    }
}
