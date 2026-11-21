package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class NativeRowTransactions {
    private NativeRowTransactions() { }

    static String actor(Connection connection, UUID account, UUID actor) throws SQLException {
        try (var query = connection.prepareStatement("SELECT updated_at FROM accounts WHERE id=? AND user_id=? AND status='ACTIVE' FOR UPDATE")) {
            query.setObject(1, account);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
                return VclCanonical.sha256("NATIVE_OWNER/1:" + account + ":" + actor + ":" + rows.getString(1));
            }
        }
    }

    static NativeDataset head(Connection connection, UUID account, UUID datasetId, long revision) throws SQLException {
        if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            return JdbcNativeDatasetCatalog.lockReadyDefinition(connection, account, datasetId, revision);
        }
        var dataset = lockThenRead(connection, account, datasetId, revision);
        NativeCatalogReadiness.verify(connection, new NativeSqlLayout(dataset));
        return dataset;
    }

    private static NativeDataset lockThenRead(Connection connection, UUID account, UUID datasetId, long revision) throws SQLException {
        try (var lock = connection.prepareStatement("SELECT id FROM cfg_dataset WHERE id=? AND account_id=? AND status='READY' FOR UPDATE")) {
            lock.setObject(1, datasetId);
            lock.setObject(2, account);
            try (var rows = lock.executeQuery()) { if (!rows.next()) throw new VclException("DATASET_UNAVAILABLE", ""); }
        }
        return JdbcNativeDatasetCatalog.definition(connection, account, datasetId, revision, true)
                .orElseThrow(() -> new VclException("SCHEMA_UNAVAILABLE", ""));
    }

    static VclException failure(SQLException error) {
        String code = switch (error.getSQLState() == null ? "" : error.getSQLState()) {
            case "23505" -> "ROW_IDENTITY_CONFLICT";
            case "22003", "22001", "23502", "23514" -> "ROW_VALUE_INVALID";
            default -> "ROW_COMMIT_UNCERTAIN";
        };
        return new VclException(code, "");
    }
}
