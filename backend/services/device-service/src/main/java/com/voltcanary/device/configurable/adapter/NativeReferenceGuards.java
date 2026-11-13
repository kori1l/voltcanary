package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;

/** No edge store is introduced for ordinary inline references. Deletion checks require actual bounded indexes. */
final class NativeReferenceGuards {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private record Reference(UUID dataset, long revision, UUID field) { }
    private NativeReferenceGuards() { }

    static void deleting(Connection connection, NativeDataset target, UUID record) throws SQLException {
        var incoming = new ArrayList<Reference>();
        try (var query = connection.prepareStatement("""
                SELECT d.id,d.schema_revision,f.field_id,f.definition_json FROM cfg_dataset d JOIN cfg_field f
                ON f.dataset_id=d.id AND f.schema_revision=d.schema_revision WHERE d.account_id=? AND d.status='READY'
                """)) {
            query.setObject(1, target.accountId());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    try {
                        var field = MAPPER.readTree(rows.getString("definition_json"));
                        if (field.at("/target/kind").asText().equals("DATASET") && field.at("/target/datasetId").asText().equals(target.schema().id().toString())) {
                            incoming.add(new Reference(rows.getObject("id", UUID.class), rows.getLong("schema_revision"), rows.getObject("field_id", UUID.class)));
                        }
                    } catch (java.io.IOException invalid) { throw new VclException("SCHEMA_INTEGRITY", ""); }
                }
            }
        }
        if (incoming.size() > 32) throw new VclException("REFERENCE_CHECK_LIMIT", "");
        for (var reference : incoming) {
            var source = JdbcNativeDatasetCatalog.definition(connection, target.accountId(), reference.dataset(), reference.revision(), true).orElseThrow();
            if (!source.schema().indexes().contains(reference.field())) throw new VclException("INDEX_REQUIRED", "");
            NativeCatalogReadiness.verify(connection, new NativeSqlLayout(source.schema()));
            String sql = "SELECT 1 FROM " + NativeSqlLayout.identifier(new NativeSqlLayout(source.schema()).table()) + " WHERE "
                    + NativeSqlLayout.identifier(NativeSqlLayout.column(reference.field())) + "=?"
                    + (reference.dataset().equals(target.schema().id()) ? " AND record_id<>?" : "") + " LIMIT 1";
            try (var query = connection.prepareStatement(sql)) {
                query.setObject(1, record);
                if (reference.dataset().equals(target.schema().id())) query.setObject(2, record);
                try (var rows = query.executeQuery()) { if (rows.next()) throw new VclException("ROW_REFERENCED", ""); }
            }
        }
    }
}
