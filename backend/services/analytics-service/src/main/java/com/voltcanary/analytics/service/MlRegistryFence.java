package com.voltcanary.analytics.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registry decisions and writes for an account share a database transaction fence. */
@Service
public class MlRegistryFence {
    private final JdbcTemplate jdbc;
    private final boolean h2;

    public MlRegistryFence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        h2 = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void account(UUID account) { lock("account:" + account); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void systemPolicy() { lock("system-policy"); }

    private void lock(String key) {
        if (h2) {
            // This table contains only the immutable key; H2's atomic upsert may safely update it.
            jdbc.update("merge into ml_registry_write_fences(scope_key) key(scope_key) values(?)", key);
        } else {
            jdbc.update("insert into ml_registry_write_fences(scope_key) values(?) on conflict(scope_key) do nothing", key);
        }
        jdbc.query("select scope_key from ml_registry_write_fences where scope_key=? for update", (rs, index) -> rs.getString(1), key);
    }
}
