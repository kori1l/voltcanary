package com.voltcanary.common.web;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local database fence: deletion and event writes serialize on the same device. */
public final class JdbcDeviceFence {
    private final JdbcTemplate jdbc;
    private final boolean h2;

    public JdbcDeviceFence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        h2 = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2")));
    }

    public boolean writable(UUID deviceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Device fence requires an owning transaction");
        }
        if (h2) {
            try {
                jdbc.update("MERGE INTO device_deletion_fences target USING (VALUES (?)) incoming(id) ON target.device_id=incoming.id "
                        + "WHEN NOT MATCHED THEN INSERT(device_id,closed) VALUES(incoming.id,false)", deviceId);
            } catch (org.springframework.dao.DuplicateKeyException concurrentInsert) {
                // H2 MERGE can race on an absent row. Its statement failure does not abort
                // the transaction; lock the committed winner below without resetting closed.
            }
        } else {
            jdbc.update("insert into device_deletion_fences(device_id,closed) values (?,false) on conflict(device_id) do nothing", deviceId);
        }
        return !Boolean.TRUE.equals(jdbc.queryForObject("select closed from device_deletion_fences where device_id=? for update", Boolean.class, deviceId));
    }

    public void close(UUID deviceId) {
        writable(deviceId);
        jdbc.update("update device_deletion_fences set closed=true where device_id=?", deviceId);
    }
}
