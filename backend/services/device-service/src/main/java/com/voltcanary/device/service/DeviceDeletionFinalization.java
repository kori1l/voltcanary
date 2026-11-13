package com.voltcanary.device.service;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceDeletionFinalization {
    private final JdbcTemplate jdbc;

    public DeviceDeletionFinalization(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void complete(UUID device) {
        Boolean purge = jdbc.queryForObject("select delete_history from device_deletions where device_id=? for update", Boolean.class, device);
        if (Boolean.TRUE.equals(purge)) {
            for (String table : List.of("source_variable_bindings", "variable_rollout_targets")) {
                jdbc.update("delete from " + table + " where data_source_id in (select id from data_sources where device_id=?)", device);
            }
            for (String table : List.of("source_processing_bundles", "operating_schedule_revisions", "operational_fact_revisions")) {
                jdbc.update("delete from " + table + " where device_id=?", device);
            }
            jdbc.update("delete from devices where id=? and deleted_at is not null", device);
        }
        jdbc.update("update device_deletions set status='COMPLETED',last_error=null,updated_at=CURRENT_TIMESTAMP where device_id=?", device);
    }
}
