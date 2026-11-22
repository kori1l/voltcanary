package com.voltcanary.telemetry.service;

import com.voltcanary.common.web.JdbcDeviceFence;
import java.util.List;
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

    public boolean writable(UUID device) {
        return fence.writable(device);
    }

    @Transactional
    public void close(UUID device, boolean purge) {
        fence.close(device);
        if (!purge) return;
        jdbc.update(
            "DELETE FROM cfg_observation WHERE binding_id IN (SELECT binding_id FROM cfg_observation_stream WHERE device_id=?)",
            device
        );
        jdbc.update("DELETE FROM cfg_observation_input WHERE device_id=?", device);
        jdbc.update(
            "DELETE FROM cfg_observation_schema WHERE binding_id IN (SELECT binding_id FROM cfg_observation_stream WHERE device_id=?)",
            device
        );
        jdbc.update("DELETE FROM cfg_observation_stream WHERE device_id=?", device);
        for (String table : List.of(
            "telemetry_quality_flags",
            "telemetry_readings",
            "telemetry.measurements",
            "telemetry.positions",
            "telemetry.vehicle_monitoring",
            "variable_measurement_lineage",
            "variable_series_catalog",
            "variable_processing_bundles"
        )) {
            jdbc.update("delete from " + table + " where device_id=?", device);
        }
        for (String table : List.of("telemetry_event_outbox", "variable_pending_events")) {
            jdbc.update("delete from " + table + " where payload_json like ?", "%" + device + "%");
        }
    }
}
