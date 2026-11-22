package com.voltcanary.alert.service;

import com.voltcanary.common.web.JdbcDeviceFence;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceHistoryDeletion {
    private final JdbcTemplate jdbc;
    private final JdbcDeviceFence fence;

    public DeviceHistoryDeletion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        fence = new JdbcDeviceFence(jdbc);
    }

    @Transactional
    public boolean writable(UUID device) { return fence.writable(device); }

    @Transactional
    public void close(UUID device, boolean purge) {
        fence.close(device);
        jdbc.update("update alert_analytics_event_inbox set status='NO_ALERT',processing_started_at=null,processed_at=CURRENT_TIMESTAMP,last_error=null where payload_json like ? and status in ('RECEIVED','PROCESSING','FAILED')", "%" + device + "%");
        jdbc.update("update alert_rules set enabled=false where scope_type='DEVICE' and scope_id=?", device);
        if (!purge) return;
        jdbc.update("delete from alert_rules where scope_type='DEVICE' and scope_id=?", device);
        jdbc.update("delete from notification_attempts where alert_id in (select id from alerts where device_id=?)", device);
        jdbc.update("delete from alert_analytics_event_inbox where payload_json like ?", "%" + device + "%");
        jdbc.update("delete from alert_events where alert_id in (select id from alerts where device_id=?)", device);
        jdbc.update("delete from cfg_rule_alert_private_evidence where device_id=?", device);
        jdbc.update("delete from alerts where device_id=?", device);
        jdbc.update("delete from alert_suppressions where device_id=?", device);
        jdbc.update("delete from maintenance_windows_read_model where device_id=?", device);
    }
}
