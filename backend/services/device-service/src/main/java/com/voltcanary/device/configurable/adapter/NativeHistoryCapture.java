package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Optional capture metadata shares the actual data/history/head transaction. No read repairs history. */
final class NativeHistoryCapture {

    static final int MAX_BASELINE_ROWS = 10_000;
    static final int MAX_STATE_BYTES = 16 * 1_048_576;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private NativeHistoryCapture() {}

    /** Establishes a complete observed baseline for existing pre-production data, before a new write. */
    static void ensure(Connection connection, NativeDataset dataset) throws SQLException {
        if (!dataset.schema().history() || registered(connection, dataset)) return;
        NativeHistoryCatalog.verify(connection, new NativeSqlLayout(dataset));
        register(connection, dataset);
        var fields = dataset
            .schema()
            .fields()
            .stream()
            .map(field -> UUID.fromString(field.path("id").asText()))
            .toList();
        int count = 0;
        long bytes = 0;
        String table = NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table());
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM " + table + " ORDER BY record_id LIMIT 10001"
            )
        ) {
            query.setFetchSize(64);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(++count <= MAX_BASELINE_ROWS, "HISTORY_BASELINE_LIMIT");
                    var current = NativeRowCodec.read(rows, dataset, fields);
                    bytes += bytes(current);
                    require(bytes <= MAX_STATE_BYTES, "HISTORY_BASELINE_LIMIT");
                    var captured = history(
                        connection,
                        dataset,
                        current.recordId(),
                        Long.parseLong(current.recordVersion()),
                        false
                    );
                    require(
                        VclCanonical.encode(MAPPER.valueToTree(current)).equals(
                            VclCanonical.encode(MAPPER.valueToTree(captured))
                        ),
                        "HISTORY_INTEGRITY"
                    );
                    append(connection, dataset, current, dataset.dataVersion(), false, "BASELINE");
                }
            }
        }
        seal(connection, dataset, dataset.dataVersion());
    }

    /** Empty creation and fully verified generation replacement have a known initial cut. */
    static void baseline(Connection connection, NativeDataset dataset) throws SQLException {
        if (!dataset.schema().history()) return;
        require(!registered(connection, dataset), "HISTORY_INTEGRITY");
        ensure(connection, dataset);
    }

    static void mutation(
        Connection connection,
        NativeDataset dataset,
        UUID record,
        long version,
        boolean deleted
    ) throws SQLException {
        if (!dataset.schema().history()) return;
        require(registered(connection, dataset), "HISTORY_INTEGRITY");
        var row = history(connection, dataset, record, version, deleted);
        append(connection, dataset, row, dataset.dataVersion() + 1, deleted, "MUTATION");
    }

    static void retirement(
        Connection connection,
        NativeDataset dataset,
        UUID record,
        long version
    ) throws SQLException {
        if (!dataset.schema().history()) return;
        append(
            connection,
            dataset,
            history(connection, dataset, record, version, true),
            dataset.dataVersion() + 1,
            true,
            "RETIREMENT"
        );
    }

    static void retire(Connection connection, NativeDataset dataset) throws SQLException {
        if (!dataset.schema().history()) return;
        ensure(connection, dataset);
        JdbcNativeDatasetCatalog.execute(
            connection,
            "UPDATE cfg_native_history_generation SET last_data_version=? WHERE dataset_id=? AND generation=? AND last_data_version IS NULL",
            dataset.dataVersion(),
            dataset.schema().id(),
            dataset.generation()
        );
    }

    static void seal(Connection connection, NativeDataset dataset, long cut) throws SQLException {
        if (!dataset.schema().history()) return;
        var records = JSON.arrayNode();
        try (
            var query = connection.prepareStatement(
                """
                SELECT record_id,record_version,deleted,value_digest FROM cfg_native_history_checkpoint
                WHERE dataset_id=? AND generation=? AND data_version=? AND capture_kind<>'RETIREMENT'
                ORDER BY record_id,record_version
                """
            )
        ) {
            query.setObject(1, dataset.schema().id());
            query.setObject(2, dataset.generation());
            query.setLong(3, cut);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(records.size() < MAX_BASELINE_ROWS, "HISTORY_BASELINE_LIMIT");
                    records
                        .addObject()
                        .put("recordId", rows.getObject(1, UUID.class).toString())
                        .put("recordVersion", Long.toString(rows.getLong(2)))
                        .put("deleted", rows.getBoolean(3))
                        .put("valueDigest", rows.getString(4));
                }
            }
        }
        JdbcNativeDatasetCatalog.execute(
            connection,
            "INSERT INTO cfg_native_history_commit(dataset_id,generation,data_version,row_count,record_set_digest) VALUES(?,?,?,?,?)",
            dataset.schema().id(),
            dataset.generation(),
            cut,
            records.size(),
            VclCanonical.sha256(VclCanonical.encode(records))
        );
    }

    static String digest(NativeDataset dataset, NativeRow row, boolean deleted) {
        var value = JSON.objectNode()
            .put("profile", "native-history-row-1")
            .put("datasetId", dataset.schema().id().toString())
            .put("generation", dataset.generation().toString())
            .put("schemaDigest", dataset.schema().digest())
            .put("deleted", deleted);
        value.set("row", MAPPER.valueToTree(row));
        return VclCanonical.sha256(VclCanonical.encode(value));
    }

    static int bytes(NativeRow row) {
        return VclCanonical.encode(MAPPER.valueToTree(row))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            .length;
    }

    static NativeRow history(
        Connection connection,
        NativeDataset dataset,
        UUID record,
        long version,
        boolean deleted
    ) throws SQLException {
        var fields = dataset
            .schema()
            .fields()
            .stream()
            .map(field -> UUID.fromString(field.path("id").asText()))
            .toList();
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM " +
                    NativeSqlLayout.identifier(new NativeSqlLayout(dataset).historyTable()) +
                    " WHERE record_id=? AND record_version=?"
            )
        ) {
            query.setObject(1, record);
            query.setLong(2, version);
            try (var row = query.executeQuery()) {
                require(row.next(), "HISTORY_INTEGRITY");
                require(row.getBoolean("deleted") == deleted, "HISTORY_INTEGRITY");
                return NativeRowCodec.read(row, dataset, fields);
            }
        }
    }

    private static void append(
        Connection connection,
        NativeDataset dataset,
        NativeRow row,
        long cut,
        boolean deleted,
        String kind
    ) throws SQLException {
        JdbcNativeDatasetCatalog.execute(
            connection,
            """
            INSERT INTO cfg_native_history_checkpoint(dataset_id,generation,record_id,record_version,data_version,deleted,capture_kind,value_digest)
            VALUES(?,?,?,?,?,?,?,?)
            """,
            dataset.schema().id(),
            dataset.generation(),
            row.recordId(),
            Long.parseLong(row.recordVersion()),
            cut,
            deleted,
            kind,
            digest(dataset, row, deleted)
        );
    }

    private static boolean registered(
        Connection connection,
        NativeDataset dataset
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT account_id,schema_revision,physical_id,last_data_version FROM cfg_native_history_generation WHERE dataset_id=? AND generation=?"
            )
        ) {
            query.setObject(1, dataset.schema().id());
            query.setObject(2, dataset.generation());
            try (var row = query.executeQuery()) {
                if (!row.next()) return false;
                require(
                    dataset.accountId().equals(row.getObject(1, UUID.class)) &&
                        dataset.schema().revision() == row.getLong(2) &&
                        dataset.physicalId().equals(row.getObject(3, UUID.class)) &&
                        row.getObject(4) == null,
                    "HISTORY_INTEGRITY"
                );
                return true;
            }
        }
    }

    private static void register(Connection connection, NativeDataset dataset) throws SQLException {
        JdbcNativeDatasetCatalog.execute(
            connection,
            "INSERT INTO cfg_native_history_generation(dataset_id,generation,account_id,schema_revision,physical_id,first_data_version) VALUES(?,?,?,?,?,?)",
            dataset.schema().id(),
            dataset.generation(),
            dataset.accountId(),
            dataset.schema().revision(),
            dataset.physicalId(),
            dataset.dataVersion()
        );
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
