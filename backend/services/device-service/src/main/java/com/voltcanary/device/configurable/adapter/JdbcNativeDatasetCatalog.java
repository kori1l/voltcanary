package com.voltcanary.device.configurable.adapter;

import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.device.configurable.port.NativeDatasetCatalog;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.util.ConcurrentLruCache;

/** Durable provisioning journal plus trusted dense DDL. No model-supplied SQL or physical names. */
@RequiredArgsConstructor
public final class JdbcNativeDatasetCatalog implements NativeDatasetCatalog {

    private record StoredSchema(UUID account, UUID dataset, String document, String digest) {}

    // Cache immutable compiler output; every transaction still reads and verifies the stored head.
    private static final ConcurrentLruCache<StoredSchema, NativeSchema> SCHEMAS =
        new ConcurrentLruCache<>(256, stored -> {
            var schema = NativeSchema.compileRevision(
                stored.document(),
                emptyMetadata(stored.account())
            );
            if (!schema.id().equals(stored.dataset()) || !schema.digest().equals(stored.digest())) {
                throw new VclException("SCHEMA_INTEGRITY", "");
            }
            return schema;
        });
    private final DataSource source;

    @Override
    public void register(
        UUID account,
        UUID actor,
        UUID generation,
        UUID recordIdFieldId,
        NativeSchema schema
    ) {
        var layout = new NativeSqlLayout(schema);
        if (
            schema
                .fields()
                .stream()
                .anyMatch(field -> field.path("id").asText().equals(recordIdFieldId.toString()))
        ) {
            throw new VclException("FIELD_SCOPE_COLLISION", "");
        }
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                timeouts(connection);
                try (
                    var lock = connection.prepareStatement(
                        "SELECT id FROM accounts WHERE id=? AND user_id=? AND status='ACTIVE' FOR UPDATE"
                    )
                ) {
                    lock.setObject(1, account);
                    lock.setObject(2, actor);
                    try (var rows = lock.executeQuery()) {
                        if (!rows.next()) throw new VclException("ACCOUNT_ACCESS_REVOKED", "");
                    }
                }
                var existing = definition(
                    connection,
                    account,
                    schema.id(),
                    schema.revision(),
                    false
                );
                if (existing.isPresent()) {
                    if (
                        !existing.get().schema().digest().equals(schema.digest()) ||
                        !existing.get().generation().equals(generation) ||
                        !existing.get().recordIdFieldId().equals(recordIdFieldId)
                    ) throw new VclException("DATASET_ID_REUSED", "");
                    connection.commit();
                    return;
                }
                try (
                    var count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM cfg_dataset WHERE account_id=?"
                    )
                ) {
                    count.setObject(1, account);
                    try (var rows = count.executeQuery()) {
                        rows.next();
                        if (rows.getLong(1) >= 100) throw new VclException("DATASET_QUOTA", "");
                    }
                }
                for (var field : schema.fields()) {
                    if (field.at("/target/kind").asText().equals("DATASET")) {
                        var target = UUID.fromString(field.at("/target/datasetId").asText());
                        if (
                            !target.equals(schema.id()) && !ownedReady(connection, account, target)
                        ) throw new VclException("REFERENCE_TARGET_UNAVAILABLE", "");
                    }
                }
                execute(
                    connection,
                    "INSERT INTO cfg_dataset(id,account_id,created_by,schema_revision,generation,data_version,record_id_field_id,physical_id,status) VALUES(?,?,?,1,?,1,?,?,'PROVISIONING')",
                    schema.id(),
                    account,
                    actor,
                    generation,
                    recordIdFieldId,
                    schema.id()
                );
                execute(
                    connection,
                    "INSERT INTO cfg_native_generation(dataset_id,generation,physical_id,account_id,schema_revision,status) VALUES(?,?,?,?,?,'PROVISIONING')",
                    schema.id(),
                    generation,
                    schema.id(),
                    account,
                    schema.revision()
                );
                execute(
                    connection,
                    "INSERT INTO cfg_schema_revision(dataset_id,revision,document_digest,canonical_document) VALUES(?,?,?,?)",
                    schema.id(),
                    schema.revision(),
                    schema.digest(),
                    schema.canonical()
                );
                for (var field : schema.fields())
                    execute(
                        connection,
                        "INSERT INTO cfg_field(dataset_id,schema_revision,field_id,definition_json) VALUES(?,?,?,?)",
                        schema.id(),
                        schema.revision(),
                        UUID.fromString(field.path("id").asText()),
                        VclCanonical.encode(field)
                    );
                for (var index : layout.indexes())
                    execute(
                        connection,
                        "INSERT INTO cfg_index_manifest(dataset_id,schema_revision,field_id,physical_name,status) VALUES(?,?,?,?,'PLANNED')",
                        schema.id(),
                        schema.revision(),
                        index.fieldId(),
                        index.name()
                    );
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw storage(failure);
        }
    }

    @Override
    public NativeDataset provision(UUID account, UUID datasetId) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                timeouts(connection);
                String status;
                try (
                    var lock = connection.prepareStatement(
                        "SELECT d.status FROM cfg_dataset d JOIN accounts a ON a.id=d.account_id WHERE d.id=? AND d.account_id=? AND a.status='ACTIVE' FOR UPDATE"
                    )
                ) {
                    lock.setObject(1, datasetId);
                    lock.setObject(2, account);
                    try (var rows = lock.executeQuery()) {
                        if (!rows.next()) throw new VclException("DATASET_UNAVAILABLE", "");
                        status = rows.getString(1);
                    }
                }
                var dataset = definition(connection, account, datasetId, 0, false).orElseThrow(() ->
                    new VclException("DATASET_UNAVAILABLE", "")
                );
                var layout = new NativeSqlLayout(dataset);
                if (status.equals("READY")) {
                    NativeCatalogReadiness.verify(connection, layout);
                    connection.commit();
                    return dataset;
                }
                execute(connection, layout.createTable());
                if (dataset.schema().history()) execute(connection, layout.createHistory());
                for (var index : layout.indexes()) execute(connection, layout.createIndex(index));
                NativeCatalogReadiness.verify(connection, layout);
                NativeHistoryCapture.ensure(connection, dataset);
                execute(
                    connection,
                    "UPDATE cfg_index_manifest SET status='READY' WHERE dataset_id=? AND schema_revision=?",
                    datasetId,
                    dataset.schema().revision()
                );
                execute(
                    connection,
                    "UPDATE cfg_dataset SET status='READY',updated_at=CURRENT_TIMESTAMP WHERE id=? AND account_id=? AND status IN ('PROVISIONING','FAILED','READY')",
                    datasetId,
                    account
                );
                execute(
                    connection,
                    "UPDATE cfg_native_generation SET status='ACTIVE' WHERE dataset_id=? AND generation=? AND status='PROVISIONING'",
                    datasetId,
                    dataset.generation()
                );
                connection.commit();
                return dataset;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw storage(failure);
        }
    }

    @Override
    public Optional<NativeDataset> ready(UUID account, UUID id, long revision) {
        try (var connection = source.getConnection()) {
            var dataset = definition(connection, account, id, revision, true);
            if (dataset.isPresent()) NativeCatalogReadiness.verify(
                connection,
                new NativeSqlLayout(dataset.get())
            );
            return dataset;
        } catch (SQLException failure) {
            throw storage(failure);
        }
    }

    @Override
    public Optional<NativeDataset> current(UUID account, UUID id) {
        return ready(account, id, 0);
    }

    @Override
    public List<ArchivedTable> archived(UUID account, UUID actor) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var result = new ArrayList<ArchivedTable>();
            try (
                var query = connection.prepareStatement(
                    """
                    SELECT d.id,d.schema_revision,s.document_digest,s.canonical_document
                    FROM cfg_dataset d JOIN cfg_schema_revision s ON s.dataset_id=d.id AND s.revision=d.schema_revision
                    WHERE d.account_id=? AND d.status='ARCHIVED'
                    ORDER BY d.id LIMIT 100
                    """
                )
            ) {
                query.setObject(1, account);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        var id = rows.getObject(1, UUID.class);
                        long revision = rows.getLong(2);
                        var schema = NativeSchema.compileRevision(
                            rows.getString(4),
                            emptyMetadata(account)
                        );
                        if (
                            !schema.id().equals(id) ||
                            schema.revision() != revision ||
                            !schema.digest().equals(rows.getString(3))
                        ) {
                            throw new VclException("SCHEMA_INTEGRITY", "");
                        }
                        result.add(
                            new ArchivedTable(
                                id,
                                revision,
                                schema.definition().path("label").asText()
                            )
                        );
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public List<NativeDataset> list(UUID account, int limit) {
        if (limit < 1 || limit > 100) throw new VclException("QUERY_LIMIT", "");
        try (
            var connection = source.getConnection();
            var query = connection.prepareStatement(
                """
                SELECT d.id,d.schema_revision FROM cfg_dataset d JOIN accounts a ON a.id=d.account_id
                WHERE d.account_id=? AND d.status='READY' AND a.status='ACTIVE' ORDER BY d.id LIMIT ?
                """
            )
        ) {
            query.setObject(1, account);
            query.setInt(2, limit);
            var result = new ArrayList<NativeDataset>();
            try (var rows = query.executeQuery()) {
                while (rows.next())
                    definition(
                        connection,
                        account,
                        rows.getObject(1, UUID.class),
                        rows.getLong(2),
                        true
                    ).ifPresent(result::add);
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw storage(failure);
        }
    }

    static Optional<NativeDataset> definition(
        Connection connection,
        UUID account,
        UUID id,
        long revision,
        boolean ready
    ) throws SQLException {
        return definition(connection, account, id, revision, ready, false);
    }

    static NativeDataset lockReadyDefinition(
        Connection connection,
        UUID account,
        UUID id,
        long revision
    ) throws SQLException {
        var result = definition(connection, account, id, 0, true, true).orElseThrow(() ->
            new VclException("DATASET_UNAVAILABLE", "")
        );
        if (revision != 0 && result.schema().revision() != revision) throw new VclException(
            "SCHEMA_UNAVAILABLE",
            ""
        );
        return result;
    }

    private static Optional<NativeDataset> definition(
        Connection connection,
        UUID account,
        UUID id,
        long revision,
        boolean ready,
        boolean lockHead
    ) throws SQLException {
        String sql =
            """
            SELECT d.generation,d.data_version,d.record_id_field_id,d.physical_id,d.status AS dataset_status,d.schema_revision,
                g.physical_id AS recorded_physical_id,g.status AS generation_status,s.document_digest,s.canonical_document
            FROM cfg_dataset d LEFT JOIN cfg_schema_revision s ON s.dataset_id=d.id AND s.revision=d.schema_revision
            JOIN accounts a ON a.id=d.account_id
            LEFT JOIN cfg_native_generation g ON g.dataset_id=d.id AND g.account_id=d.account_id AND g.generation=d.generation
            WHERE d.id=? AND d.account_id=? AND (d.schema_revision=? OR ?=0) AND a.status='ACTIVE'
            """ + (ready ? " AND d.status='READY'" : " AND d.status<>'ARCHIVED'");
        if (lockHead) sql = NativeCatalogReadiness.withLockedHead(sql + " FOR UPDATE OF d");
        try (var query = connection.prepareStatement(sql)) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setLong(3, revision);
            query.setLong(4, revision);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String document = rows.getString("canonical_document");
                if (document == null) {
                    if (lockHead) throw new VclException("SCHEMA_UNAVAILABLE", "");
                    return Optional.empty();
                }
                if (
                    !rows
                        .getObject("physical_id", UUID.class)
                        .equals(rows.getObject("recorded_physical_id", UUID.class)) ||
                    !java.util.Set.of("ACTIVE", "PROVISIONING").contains(
                        rows.getString("generation_status") == null
                            ? ""
                            : rows.getString("generation_status")
                    ) ||
                    (rows.getString("dataset_status").equals("READY") &&
                        !rows.getString("generation_status").equals("ACTIVE"))
                ) {
                    throw new VclException("NATIVE_CATALOG_DRIFT", "");
                }
                var schema = SCHEMAS.get(
                    new StoredSchema(account, id, document, rows.getString("document_digest"))
                );
                if (schema.revision() != rows.getLong("schema_revision")) throw new VclException(
                    "SCHEMA_INTEGRITY",
                    ""
                );
                var dataset = new NativeDataset(
                    account,
                    rows.getObject("generation", UUID.class),
                    rows.getLong("data_version"),
                    rows.getObject("record_id_field_id", UUID.class),
                    schema,
                    rows.getObject("physical_id", UUID.class)
                );
                if (lockHead) NativeCatalogReadiness.verifyLockedHead(
                    rows.getString("physical_catalog"),
                    new NativeSqlLayout(dataset)
                );
                return Optional.of(dataset);
            }
        }
    }

    private static boolean ownedReady(
        Connection connection,
        UUID account,
        UUID id
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT 1 FROM cfg_dataset WHERE account_id=? AND id=? AND status='READY'"
            )
        ) {
            query.setObject(1, account);
            query.setObject(2, id);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    public static VclMetadata emptyMetadata(UUID account) {
        return new VclMetadata() {
            public String accessScopeDigest() {
                return VclCanonical.sha256("NATIVE_SCHEMA/1:" + account);
            }

            public Optional<Snapshot> dataset(UUID id, long revision) {
                return Optional.empty();
            }

            public Optional<Snapshot> binding(UUID id, long revision) {
                return Optional.empty();
            }

            public boolean permits(Resource kind, UUID id) {
                return false;
            }
        };
    }

    static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    static void timeouts(Connection connection) throws SQLException {
        if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            execute(connection, "SET LOCAL lock_timeout='2s'");
            execute(connection, "SET LOCAL statement_timeout='10s'");
        }
    }

    static VclException storage(SQLException failure) {
        return new VclException(
            "23505".equals(failure.getSQLState())
                ? "DATASET_ID_REUSED"
                : "NATIVE_STORAGE_UNAVAILABLE",
            ""
        );
    }
}
