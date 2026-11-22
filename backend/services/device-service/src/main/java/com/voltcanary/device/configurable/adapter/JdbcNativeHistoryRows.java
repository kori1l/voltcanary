package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeHistoryRows;
import com.voltcanary.device.configurable.port.NativeWorkspaceIdentity;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Explicit generation/cut reads of captured immutable versions, with current actor and field/row authority. */
@RequiredArgsConstructor
public final class JdbcNativeHistoryRows implements NativeHistoryRows {

    private static final VclParser PARSER = new VclParser();
    private static final VclCompiler COMPILER = new VclCompiler(
        new VclRegistry(PARSER),
        VclUnits.standard()
    );
    private final DataSource source;
    private final NativeQueryCursor cursors;
    private final NativeWorkspaceIdentity identity;

    @Override
    public Generations generations(
        UUID account,
        UUID actor,
        UUID dataset,
        UUID afterGeneration,
        int limit
    ) {
        return generations(
            NativeDataTransactions.owner(source),
            account,
            actor,
            dataset,
            afterGeneration,
            limit
        );
    }

    @Override
    public Generations generations(NativeSharedAccess access, UUID afterGeneration, int limit) {
        return generations(
            NativeDataTransactions.shared(source, identity, access),
            access.account(),
            access.actor(),
            access.dataset(),
            afterGeneration,
            limit
        );
    }

    private Generations generations(
        NativeDataTransactions transactions,
        UUID account,
        UUID actor,
        UUID dataset,
        UUID afterGeneration,
        int limit
    ) {
        require(limit >= 1 && limit <= 100, "HISTORY_REQUEST_INVALID");
        return transactions.run(account, actor, (connection, scope) -> {
            historyPermission(scope);
            try (
                var owned = connection.prepareStatement(
                    "SELECT 1 FROM cfg_dataset WHERE account_id=? AND id=?"
                )
            ) {
                owned.setObject(1, account);
                owned.setObject(2, dataset);
                try (var row = owned.executeQuery()) {
                    require(row.next(), "DATASET_UNAVAILABLE");
                }
            }
            var result = new ArrayList<Generation>();
            String sql =
                """
                SELECT g.generation,g.schema_revision,s.document_digest,g.status,h.first_data_version,
                    COALESCE(h.last_data_version,d.data_version)
                FROM cfg_native_generation g
                JOIN cfg_native_history_generation h ON h.dataset_id=g.dataset_id AND h.generation=g.generation
                    AND h.account_id=g.account_id AND h.schema_revision=g.schema_revision AND h.physical_id=g.physical_id
                JOIN cfg_schema_revision s ON s.dataset_id=g.dataset_id AND s.revision=g.schema_revision
                JOIN cfg_dataset d ON d.account_id=g.account_id AND d.id=g.dataset_id
                WHERE g.account_id=? AND g.dataset_id=? AND g.reclaimed_at IS NULL
                    AND ((g.status='ACTIVE' AND h.last_data_version IS NULL AND g.generation=d.generation)
                      OR (g.status IN ('RETIRED','ARCHIVED') AND h.last_data_version IS NOT NULL))
                """ +
                (scope.policy() == null ? "" : " AND g.schema_revision=d.schema_revision") +
                (afterGeneration == null ? "" : " AND g.generation>?") +
                " ORDER BY g.generation LIMIT ?";
            try (var query = connection.prepareStatement(sql)) {
                query.setObject(1, account);
                query.setObject(2, dataset);
                int parameter = 3;
                if (afterGeneration != null) query.setObject(parameter++, afterGeneration);
                query.setInt(parameter, limit + 1);
                query.setQueryTimeout(2);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        long first = rows.getLong(5);
                        long last = rows.getLong(6);
                        result.add(
                            new Generation(
                                rows.getObject(1, UUID.class),
                                rows.getLong(2),
                                rows.getString(3),
                                rows.getString(4),
                                first,
                                last
                            )
                        );
                    }
                }
            }
            UUID next = null;
            if (result.size() > limit) {
                result.removeLast();
                next = result.getLast().generation();
            }
            return new Generations(result, next);
        });
    }

    @Override
    public HistoricalSchema schema(UUID account, UUID actor, UUID dataset, UUID generation) {
        return schema(NativeDataTransactions.owner(source), account, actor, dataset, generation);
    }

    @Override
    public HistoricalSchema schema(NativeSharedAccess access, UUID generation) {
        return schema(
            NativeDataTransactions.shared(source, identity, access),
            access.account(),
            access.actor(),
            access.dataset(),
            generation
        );
    }

    private HistoricalSchema schema(
        NativeDataTransactions transactions,
        UUID account,
        UUID actor,
        UUID dataset,
        UUID generation
    ) {
        return transactions.run(account, actor, (connection, scope) -> {
            historyPermission(scope);
            var retained = NativeHistoricalDatasets.load(connection, account, dataset, generation);
            requireSharedRevision(connection, scope, retained);
            var range = NativeHistoryEvidence.range(connection, retained);
            var definition =
                scope.policy() == null ? retained.schema().definition() : scope.metadata(retained);
            return new HistoricalSchema(
                retained.recordIdFieldId(),
                retained.schema().digest(),
                definition,
                range.first(),
                range.through(),
                NativeHistoryEvidence.MAX_CUTS
            );
        });
    }

    public static boolean ready(DataSource source, NativeDataset dataset) {
        if (!dataset.schema().history()) return false;
        try (var connection = source.getConnection()) {
            NativeHistoryCatalog.verify(connection, new NativeSqlLayout(dataset));
            NativeHistoryEvidence.verifyPoint(
                connection,
                dataset,
                dataset.dataVersion(),
                false,
                dataset.dataVersion() == NativeHistoryEvidence.range(connection, dataset).first()
                    ? "BASELINE"
                    : "MUTATION"
            );
            return true;
        } catch (SQLException | VclException unavailable) {
            return false;
        }
    }

    @Override
    public Revision revision(
        UUID account,
        UUID actor,
        UUID dataset,
        UUID generation,
        UUID record,
        long version,
        List<UUID> select
    ) {
        return revision(
            NativeDataTransactions.owner(source),
            account,
            actor,
            dataset,
            generation,
            record,
            version,
            select
        );
    }

    @Override
    public Revision revision(
        NativeSharedAccess access,
        UUID generation,
        UUID record,
        long version,
        List<UUID> select
    ) {
        return revision(
            NativeDataTransactions.shared(source, identity, access),
            access.account(),
            access.actor(),
            access.dataset(),
            generation,
            record,
            version,
            select
        );
    }

    @Override
    public NativeQueryPage query(
        UUID account,
        UUID actor,
        UUID generation,
        long through,
        VclDocument document
    ) {
        return query(
            NativeDataTransactions.owner(source),
            account,
            actor,
            generation,
            through,
            document
        );
    }

    @Override
    public NativeQueryPage query(
        NativeSharedAccess access,
        UUID generation,
        long through,
        VclDocument document
    ) {
        return query(
            NativeDataTransactions.shared(source, identity, access),
            access.account(),
            access.actor(),
            generation,
            through,
            document
        );
    }

    private Revision revision(
        NativeDataTransactions transactions,
        UUID account,
        UUID actor,
        UUID datasetId,
        UUID generation,
        UUID record,
        long version,
        List<UUID> select
    ) {
        require(
            version > 0 &&
                select != null &&
                !select.isEmpty() &&
                select.size() <= 64 &&
                select.stream().distinct().count() == select.size(),
            "HISTORY_REQUEST_INVALID"
        );
        return transactions.run(account, actor, (connection, access) -> {
            access.target(datasetId);
            historyPermission(access);
            var dataset = NativeHistoricalDatasets.load(connection, account, datasetId, generation);
            requireSharedRevision(connection, access, dataset);
            for (UUID field : select) {
                require(field != null, "HISTORY_REQUEST_INVALID");
                if (!field.equals(dataset.recordIdFieldId())) {
                    dataset.schema().field(field);
                    require(
                        access.policy() == null || access.policy().readFields().contains(field),
                        "DATA_ACCESS_DENIED"
                    );
                }
            }
            require(
                access.policy() == null || access.policy().permits(record),
                "DATA_ACCESS_DENIED"
            );
            try (
                var query = connection.prepareStatement(
                    "SELECT h.*,p.data_version AS captured_cut,p.deleted AS captured_deleted,p.value_digest AS captured_digest,p.capture_kind " +
                        "FROM cfg_native_history_checkpoint p LEFT JOIN " +
                        NativeSqlLayout.identifier(new NativeSqlLayout(dataset).historyTable()) +
                        " h ON h.record_id=p.record_id AND h.record_version=p.record_version WHERE p.dataset_id=? AND p.generation=? AND p.record_id=? AND p.record_version=?"
                )
            ) {
                query.setObject(1, datasetId);
                query.setObject(2, generation);
                query.setObject(3, record);
                query.setLong(4, version);
                query.setQueryTimeout(2);
                try (var rows = query.executeQuery()) {
                    require(rows.next(), "HISTORY_REVISION_UNAVAILABLE");
                    require(
                        rows.getObject("record_id") != null &&
                            rows.getBoolean("deleted") == rows.getBoolean("captured_deleted"),
                        "HISTORY_INTEGRITY"
                    );
                    var full = NativeRowCodec.read(
                        rows,
                        dataset,
                        dataset
                            .schema()
                            .fields()
                            .stream()
                            .map(field -> UUID.fromString(field.path("id").asText()))
                            .toList()
                    );
                    require(
                        NativeHistoryCapture.digest(
                            dataset,
                            full,
                            rows.getBoolean("deleted")
                        ).equals(rows.getString("captured_digest")),
                        "HISTORY_INTEGRITY"
                    );
                    NativeHistoryEvidence.verifyPoint(
                        connection,
                        dataset,
                        rows.getLong("captured_cut"),
                        rows.getBoolean("deleted"),
                        rows.getString("capture_kind")
                    );
                    var projected = NativeRowCodec.read(rows, dataset, select);
                    require(
                        NativeHistoryCapture.bytes(projected) <= NativeQuerySnapshots.MAX_BYTES,
                        "HISTORY_BUDGET_EXCEEDED"
                    );
                    return new Revision(
                        datasetId,
                        dataset.schema().revision(),
                        generation,
                        rows.getLong("captured_cut"),
                        projected,
                        rows.getBoolean("deleted"),
                        rows.getObject("recorded_at", OffsetDateTime.class).toInstant().toString(),
                        rows.getString("capture_kind"),
                        dataset.schema().digest(),
                        access.digest()
                    );
                }
            }
        });
    }

    private NativeQueryPage query(
        NativeDataTransactions transactions,
        UUID account,
        UUID actor,
        UUID generation,
        long through,
        VclDocument document
    ) {
        require(
            through >= 1 &&
                through <= 9_007_199_254_740_991L &&
                document.kind() == VclDocument.Kind.QUERY,
            "HISTORY_REQUEST_INVALID"
        );
        require(
            document.spec().at("/read/mode").asText().equals("LIVE"),
            "HISTORY_TEMPLATE_REQUIRED"
        );
        return transactions.run(account, actor, (connection, access) -> {
            historyPermission(access);
            UUID id = UUID.fromString(document.spec().at("/target/datasetId").asText());
            access.target(id);
            var retained = NativeHistoricalDatasets.load(connection, account, id, generation);
            requireSharedRevision(connection, access, retained);
            require(
                retained.schema().revision() ==
                    document.spec().at("/target/schemaRevision").longValue(),
                "SCHEMA_REVISION_CONFLICT"
            );
            var coverage = NativeHistoryEvidence.verify(connection, retained, through);
            var pinned = new NativeDataset(
                account,
                generation,
                through,
                retained.recordIdFieldId(),
                retained.schema(),
                retained.physicalId()
            );
            var state = NativeHistoryState.sql(pinned, access, coverage.first(), through);
            var spec = PARSER.parse(VclCanonical.json(document)).spec();
            compile(pinned, access, document);
            require(
                spec.path("order").size() == 1 &&
                    spec
                        .at("/order/0/fieldId")
                        .asText()
                        .equals(pinned.recordIdFieldId().toString()),
                "QUERY_ORDER_UNSUPPORTED"
            );
            var digestDocument = document.json();
            ((ObjectNode) digestDocument.path("spec")).putNull("cursor");
            String digest = VclCanonical.sha256(
                "NATIVE_HISTORY_QUERY/1:" +
                    generation +
                    ":" +
                    through +
                    ":" +
                    VclCanonical.documentDigest(PARSER.parse(digestDocument.toString()))
            );
            var position = spec.path("cursor").isNull()
                ? cursors.first(pinned)
                : cursors.decode(
                      spec.path("cursor").asText(),
                      actor,
                      pinned,
                      access.digest(),
                      digest
                  );
            require(
                position.initialDataVersion() == through && !position.drifted(),
                "CURSOR_CONFLICT"
            );
            NativeHistoryState.verify(connection, pinned, state);
            var rows = select(connection, pinned, state, spec, position.recordId());
            boolean more = rows.size() > spec.path("limit").intValue();
            if (more) rows.removeLast();
            String next = more
                ? cursors.encode(
                      actor,
                      pinned,
                      access.digest(),
                      digest,
                      new NativeQueryCursor.Position(
                          rows.getLast().recordId(),
                          through,
                          false,
                          position.expiresAt()
                      )
                  )
                : null;
            var manifest = new NativeQueryPage.ReadManifest(
                id,
                pinned.schema().revision(),
                generation,
                through,
                pinned.schema().digest(),
                access.digest(),
                digest,
                "HISTORY",
                null
            );
            return new NativeQueryPage(
                rows,
                next,
                manifest,
                more ? "PARTIAL" : "COMPLETE",
                more ? "PAGE_LIMIT" : null
            );
        });
    }

    private static void compile(
        NativeDataset dataset,
        NativeReadScope access,
        VclDocument document
    ) {
        COMPILER.compile(
            document,
            new VclMetadata() {
                public String accessScopeDigest() {
                    return access.digest();
                }

                public Optional<Snapshot> dataset(UUID id, long revision) {
                    return id.equals(dataset.schema().id()) &&
                        revision == dataset.schema().revision()
                        ? Optional.of(new Snapshot(id, revision, access.metadata(dataset)))
                        : Optional.empty();
                }

                public Optional<Snapshot> binding(UUID id, long revision) {
                    return Optional.empty();
                }

                public boolean permits(Resource kind, UUID id) {
                    return false;
                }
            }
        );
    }

    private static ArrayList<NativeRow> select(
        Connection connection,
        NativeDataset dataset,
        NativeHistoryState.Sql state,
        JsonNode spec,
        UUID after
    ) throws SQLException {
        var builder = new NativeQuerySql(dataset);
        String predicate = builder.predicate(spec.path("where"));
        boolean ascending = spec.at("/order/0/direction").asText().equals("ASC");
        var fields = new ArrayList<UUID>();
        spec.path("select").forEach(value -> fields.add(UUID.fromString(value.asText())));
        var columns = new java.util.LinkedHashSet<String>(List.of("record_id", "record_version"));
        fields.forEach(field -> columns.addAll(NativeRowCodec.columns(dataset, field)));
        String sql =
            state.cte() +
            ", historical_state AS (SELECT h.* FROM chosen c JOIN " +
            state.table() +
            " h ON h.record_id=c.record_id AND h.record_version=c.record_version) SELECT " +
            columns
                .stream()
                .map(NativeSqlLayout::identifier)
                .collect(java.util.stream.Collectors.joining(",")) +
            " FROM historical_state WHERE deleted=FALSE AND (" +
            predicate +
            ")" +
            (after == null ? "" : ascending ? " AND record_id>?" : " AND record_id<?") +
            " ORDER BY record_id " +
            (ascending ? "ASC" : "DESC") +
            " LIMIT ?";
        var result = new ArrayList<NativeRow>();
        long bytes = 2;
        try (var query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(2);
            int parameter = 1;
            for (Object value : state.parameters()) query.setObject(parameter++, value);
            for (Object value : builder.parameters()) query.setObject(parameter++, value);
            if (after != null) query.setObject(parameter++, after);
            query.setInt(parameter, spec.path("limit").intValue() + 1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var row = NativeRowCodec.read(rows, dataset, fields);
                    if (result.size() < spec.path("limit").intValue()) bytes +=
                        NativeHistoryCapture.bytes(row) + 1;
                    require(bytes <= NativeQuerySnapshots.MAX_BYTES, "HISTORY_BUDGET_EXCEEDED");
                    result.add(row);
                }
            }
        }
        return result;
    }

    private static void historyPermission(NativeReadScope access) {
        require(
            access.policy() == null || access.policy().actions().contains("HISTORY"),
            "DATA_ACCESS_DENIED"
        );
    }

    private static void requireSharedRevision(
        Connection connection,
        NativeReadScope access,
        NativeDataset retained
    ) throws SQLException {
        if (access.policy() == null) return;
        var current = NativeRowTransactions.head(
            connection,
            retained.accountId(),
            retained.schema().id(),
            0
        );
        require(current.schema().revision() == retained.schema().revision(), "DATA_ACCESS_DENIED");
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
