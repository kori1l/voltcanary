package com.voltcanary.simulator.service;

import com.voltcanary.common.web.JdbcDeviceFence;
import com.voltcanary.simulator.runner.RunningSimulationRegistry;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceHistoryDeletion {

    private final JdbcTemplate jdbc;
    private final RunningSimulationRegistry registry;
    private final JdbcDeviceFence fence;

    public DeviceHistoryDeletion(JdbcTemplate jdbc, RunningSimulationRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
        fence = new JdbcDeviceFence(jdbc);
    }

    public boolean writable(UUID device) {
        return fence.writable(device);
    }

    @Transactional
    public void close(UUID device, boolean purge) {
        fence.close(device);
        registry.remove(device).ifPresent(future -> future.cancel(false));
        jdbc.update(
            "update simulation_runs set status='STOPPED', active_key=null, lease_until=null, stopped_at=CURRENT_TIMESTAMP, stop_reason='Device deleted' where device_id=? and active_key is not null",
            device
        );
        jdbc.update("update simulation_configs set source_token=null where device_id=?", device);
        if (purge) jdbc.update("delete from simulation_configs where device_id=?", device);
    }
}
