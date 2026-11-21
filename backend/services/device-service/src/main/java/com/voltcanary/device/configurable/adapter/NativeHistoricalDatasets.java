package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Explicit published generation and its immutable schema; no routing through the current physical table. */
final class NativeHistoricalDatasets {
    private NativeHistoricalDatasets() { }

    static NativeDataset load(Connection connection, UUID account, UUID dataset, UUID generation) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT g.physical_id,g.schema_revision,d.record_id_field_id,d.data_version,s.document_digest,
                    CASE WHEN OCTET_LENGTH(s.canonical_document)<=1048576 THEN s.canonical_document ELSE NULL END
                FROM cfg_native_generation g JOIN cfg_dataset d ON d.id=g.dataset_id AND d.account_id=g.account_id
                JOIN cfg_schema_revision s ON s.dataset_id=g.dataset_id AND s.revision=g.schema_revision
                JOIN accounts a ON a.id=g.account_id AND a.status='ACTIVE'
                WHERE g.dataset_id=? AND g.generation=? AND g.account_id=? AND g.status IN ('ACTIVE','RETIRED','ARCHIVED')
                    AND g.reclaimed_at IS NULL
                """)) {
            query.setObject(1, dataset);
            query.setObject(2, generation);
            query.setObject(3, account);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new VclException("HISTORY_GENERATION_UNAVAILABLE", "");
                String canonical = row.getString(6);
                if (canonical == null) throw new VclException("HISTORY_INTEGRITY", "");
                var schema = NativeSchema.compileRevision(canonical, JdbcNativeDatasetCatalog.emptyMetadata(account));
                if (!schema.id().equals(dataset) || schema.revision() != row.getLong(2) || !schema.digest().equals(row.getString(5))) {
                    throw new VclException("HISTORY_INTEGRITY", "");
                }
                if (!schema.history()) throw new VclException("HISTORY_DISABLED", "");
                var result = new NativeDataset(account, generation, row.getLong(4), row.getObject(3, UUID.class), schema, row.getObject(1, UUID.class));
                NativeHistoryCatalog.verify(connection, new NativeSqlLayout(result));
                return result;
            }
        }
    }
}
