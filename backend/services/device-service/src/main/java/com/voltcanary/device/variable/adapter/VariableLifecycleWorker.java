package com.voltcanary.device.variable.adapter;

import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.variable.application.VariableDefaults;
import com.voltcanary.device.variable.application.VariableRollouts;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VariableLifecycleWorker {
    private static final Logger LOG = LoggerFactory.getLogger(VariableLifecycleWorker.class);
    private final JdbcTemplate jdbc;
    private final VariableRollouts rollouts;
    private final VariableDefaults defaults;
    private String defaultCursor = "";

    public VariableLifecycleWorker(JdbcTemplate jdbc, VariableRollouts rollouts, VariableDefaults defaults) {
        this.jdbc = jdbc;
        this.rollouts = rollouts;
        this.defaults = defaults;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void runOnce() {
        jdbc.query("""
                select r.id,r.variable_id,v.device_type,a.user_id from variable_rollout_targets r
                join device_variables v on v.id=r.variable_id join accounts a on a.id=v.account_id
                where r.status='PENDING' and a.status='ACTIVE' order by r.updated_at,r.id limit 50
                """, (row, index) -> {
            UUID id = row.getObject("id", UUID.class);
            try {
                rollouts.resume(actor(row.getObject("user_id", UUID.class)), row.getString("device_type"),
                        row.getObject("variable_id", UUID.class), id);
            } catch (RuntimeException failure) {
                LOG.warn("Variable rollout temporarily unavailable targetId={} cause={}", id,
                        failure.getClass().getSimpleName());
            }
            return id;
        });
        var scannedDefaults = jdbc.query("""
                select v.id,v.device_type,a.user_id from device_variables v
                join device_variable_default_bindings b on b.variable_id=v.id
                join accounts a on a.id=v.account_id
                where v.archived=false and a.status='ACTIVE' and cast(v.id as varchar)>?
                order by cast(v.id as varchar) limit 50
                """, (row, index) -> {
            UUID id = row.getObject("id", UUID.class);
            defaultCursor = id.toString();
            try { defaults.reconcile(actor(row.getObject("user_id", UUID.class)), row.getString("device_type"), id); }
            catch (RuntimeException failure) {
                LOG.warn("Variable inheritance temporarily unavailable variableId={} cause={}", id,
                        failure.getClass().getSimpleName());
            }
            return id;
        }, defaultCursor);
        if (scannedDefaults.isEmpty()) defaultCursor = "";
    }

    private AuthenticatedUser actor(UUID id) {
        return new AuthenticatedUser(id, null, "USER");
    }
}
