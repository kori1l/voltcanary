package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.device.configurable.port.NativeRowQueries;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.util.ConcurrentLruCache;

public final class JdbcNativeRowQueries implements NativeRowQueries {
    private final NativeDataTransactions transactions;
    private final NativeQueryCursor cursors;
    private final VclParser parser = new VclParser();
    private final VclCompiler compiler = new VclCompiler(new VclRegistry(parser), VclUnits.standard());
    private final ConcurrentLruCache<CompileRequest, CompiledQuery> compiled = new ConcurrentLruCache<>(128, this::compile);

    public JdbcNativeRowQueries(DataSource source, NativeQueryCursor cursors) {
        this(cursors, NativeDataTransactions.owner(source));
    }

    JdbcNativeRowQueries(NativeQueryCursor cursors, NativeDataTransactions transactions) {
        this.transactions = transactions;
        this.cursors = cursors;
    }

    @Override
    public NativeQueryPage query(UUID account, UUID actor, VclDocument document) {
        if (document.kind() != VclDocument.Kind.QUERY) throw new VclException("QUERY_REQUIRED", "");
        return transactions.run(account, actor, (connection, access) -> {
            String scope = access.digest();
            var query = prepare(connection, account, actor, access, document);
            var spec = query.spec();
            var dataset = query.dataset();
            String digest = query.digest();
            if (spec.at("/read/mode").asText().equals("SNAPSHOT")) {
                return snapshotPage(connection, actor, scope, query);
            }
            var position = spec.path("cursor").isNull() ? cursors.first(dataset)
                    : cursors.decode(spec.path("cursor").asText(), actor, dataset, scope, digest);
            int limit = spec.path("limit").asInt();
            var rows = select(connection, query, position.recordId(), limit + 1);
            boolean more = rows.size() > limit;
            if (more) rows.removeLast();
            boolean drifted = position.drifted() || dataset.dataVersion() != position.initialDataVersion();
            String next = more ? cursors.encode(actor, dataset, scope, digest,
                    new NativeQueryCursor.Position(rows.getLast().recordId(), position.initialDataVersion(), drifted, position.expiresAt())) : null;
            String completeness = more ? "PARTIAL" : drifted ? "UNKNOWN" : "COMPLETE";
            return new NativeQueryPage(rows, next, manifest(dataset, scope, digest, null),
                    completeness, more ? "PAGE_LIMIT" : drifted ? "LIVE_DATA_CHANGED" : null);
        });
    }

    @Override
    public Snapshot seal(UUID account, UUID actor, VclDocument document) {
        return seal(account, actor, document, null);
    }

    @Override
    public Snapshot seal(UUID account, UUID actor, VclDocument document, AsOfProjection projection) {
        if (document.kind() != VclDocument.Kind.QUERY) throw new VclException("QUERY_REQUIRED", "");
        if (!document.spec().at("/read/mode").asText().equals("LIVE") || !document.spec().path("cursor").isNull()) {
            throw new VclException("SNAPSHOT_TEMPLATE_REQUIRED", "");
        }
        return transactions.run(account, actor, (connection, access) -> {
            String scope = access.digest();
            var query = prepare(connection, account, actor, access, document);
            String projectionDigest = projection == null ? null
                    : NativeSnapshotAsOf.projectionDigest(query.dataset(), query.spec().path("select"), projection);
            var rows = select(connection, query, null, NativeQuerySnapshots.MAX_ROWS + 1);
            var sealed = NativeQuerySnapshots.insert(connection, query.dataset(), actor, scope, query.digest(), rows, cursors.now(), projectionDigest);
            if (projection != null) NativeSnapshotAsOf.index(connection, sealed.snapshotId(), rows, projection);
            return sealed;
        });
    }

    @Override
    public AsOf asOf(UUID account, UUID actor, VclDocument document, UUID effectiveIntervalField, Instant eventTime,
            Map<UUID, JsonNode> keys) {
        return withAsOfSnapshot(account, actor, document, projection(effectiveIntervalField, keys),
                (connection, pinned, stored, manifest) ->
                        NativeSnapshotAsOf.resolve(connection, pinned, eventTime, keys, stored.rows(), manifest));
    }

    @Override
    public AsOfSplit splitAsOf(UUID account, UUID actor, VclDocument document, UUID effectiveIntervalField,
            Instant start, Instant end, Map<UUID, JsonNode> keys) {
        return withAsOfSnapshot(account, actor, document, projection(effectiveIntervalField, keys),
                (connection, pinned, stored, manifest) ->
                        NativeSnapshotAsOf.split(connection, pinned, start, end, keys, stored.rows(), manifest));
    }

    @Override
    public NativeQueryPage.ReadManifest inspectAsOf(UUID account, UUID actor, VclDocument document, AsOfProjection projection) {
        return withAsOfSnapshot(account, actor, document, projection, (connection, pinned, stored, manifest) -> manifest);
    }

    private static AsOfProjection projection(UUID interval, Map<UUID, JsonNode> keys) {
        if (keys == null) throw new VclException("ASOF_FIELDS_INVALID", "");
        return new AsOfProjection(interval, List.copyOf(keys.keySet()));
    }

    @FunctionalInterface
    private interface AsOfRead<T> {
        T read(Connection connection, NativeDataset dataset, NativeQuerySnapshots.Stored stored,
                NativeQueryPage.ReadManifest manifest) throws SQLException;
    }

    private <T> T withAsOfSnapshot(UUID account, UUID actor, VclDocument document, AsOfProjection projection, AsOfRead<T> read) {
        if (document.kind() != VclDocument.Kind.QUERY || !"SNAPSHOT".equals(document.spec().at("/read/mode").textValue())
                || !document.spec().path("cursor").isNull()) {
            throw new VclException("ASOF_SNAPSHOT_REQUIRED", "");
        }
        return transactions.run(account, actor, (connection, access) -> {
            String scope = access.digest();
            var query = prepare(connection, account, actor, access, document);
            var current = query.dataset();
            UUID id = UUID.fromString(query.spec().at("/read/snapshotId").textValue());
            var stored = NativeQuerySnapshots.read(connection, current, actor, scope, query.digest(), id, cursors.now());
            var pinned = new NativeDataset(current.accountId(), stored.generation(), stored.dataVersion(), current.recordIdFieldId(), current.schema());
            String projectionDigest = NativeSnapshotAsOf.projectionDigest(pinned, query.spec().path("select"), projection);
            if (!projectionDigest.equals(stored.projectionDigest())) throw new VclException("ASOF_PROJECTION_CONFLICT", "");
            return read.read(connection, pinned, stored, manifest(pinned, scope, query.digest(), id));
        });
    }

    private record Prepared(NativeDataset dataset, JsonNode spec, String digest, NativeReadScope access) { }
    private record CompileRequest(String document, NativeDataset dataset, NativeReadScope access) { }
    private record CompiledQuery(JsonNode spec, String digest) { }

    private Prepared prepare(Connection connection, UUID account, UUID actor, NativeReadScope access, VclDocument document) throws SQLException {
        String scope = access.digest();
        var spec = document.spec();
        UUID id = UUID.fromString(spec.at("/target/datasetId").asText());
        access.target(id);
        long revision = spec.at("/target/schemaRevision").asLong();
        var dataset = spec.at("/read/mode").asText().equals("SNAPSHOT")
                ? NativeQuerySnapshots.pinnedDataset(connection, account, actor, id, revision,
                        UUID.fromString(spec.at("/read/snapshotId").asText()), scope, cursors.now())
                : NativeRowTransactions.head(connection, account, id, revision);
        // Reuse semantic validation only after the fresh transaction has established the
        // complete input, physical head and exact access scope.
        var request = new CompileRequest(document.json().toString(), dataset, access);
        var selection = request.document().length() <= 16_384 ? compiled.get(request) : compile(request);
        return new Prepared(dataset, selection.spec(), selection.digest(), access);
    }

    private CompiledQuery compile(CompileRequest request) {
        var dataset = request.dataset();
        var access = request.access();
        var normalized = parser.parse(VclCanonical.json(parser.parse(request.document())));
        var spec = normalized.spec();
        UUID datasetId = dataset.schema().id();
        long revision = spec.at("/target/schemaRevision").asLong();
        var metadata = new VclMetadata() {
            public String accessScopeDigest() { return access.digest(); }
            public Optional<Snapshot> dataset(UUID requested, long version) {
                return requested.equals(datasetId) && version == revision
                        ? Optional.of(new Snapshot(datasetId, revision, access.metadata(dataset))) : Optional.empty();
            }
            public Optional<Snapshot> binding(UUID requested, long version) { return Optional.empty(); }
            public boolean permits(Resource kind, UUID requested) { return false; }
        };
        compiler.compile(normalized, metadata);
        if (spec.path("order").size() != 1 || !spec.at("/order/0/fieldId").asText().equals(dataset.recordIdFieldId().toString())) {
            throw new VclException("QUERY_ORDER_UNSUPPORTED", "");
        }
        var digestDocument = normalized.json();
        var digestSpec = (ObjectNode) digestDocument.path("spec");
        digestSpec.putNull("cursor");
        digestSpec.putObject("read").put("mode", "LIVE");
        return new CompiledQuery(spec, VclCanonical.documentDigest(parser.parse(digestDocument.toString())));
    }

    private NativeQueryPage snapshotPage(Connection connection, UUID actor, String scope, Prepared query) throws SQLException {
        var spec = query.spec();
        var current = query.dataset();
        UUID id = UUID.fromString(spec.at("/read/snapshotId").textValue());
        var stored = NativeQuerySnapshots.read(connection, current, actor, scope, query.digest(), id, cursors.now());
        var pinned = new NativeDataset(current.accountId(), stored.generation(), stored.dataVersion(), current.recordIdFieldId(), current.schema());
        String cursorDigest = VclCanonical.sha256("NATIVE_QUERY_SNAPSHOT/1\n" + id + "\n" + query.digest());
        var rows = stored.rows();
        int start = 0;
        if (!spec.path("cursor").isNull()) {
            var position = cursors.decode(spec.path("cursor").asText(), actor, pinned, scope, cursorDigest);
            while (start < rows.size() && !rows.get(start).recordId().equals(position.recordId())) start++;
            if (start == rows.size()) throw new VclException("CURSOR_CONFLICT", "");
            start++;
        }
        int end = Math.min(rows.size(), start + spec.path("limit").intValue());
        boolean more = end < rows.size();
        String next = more ? cursors.encode(actor, pinned, scope, cursorDigest,
                new NativeQueryCursor.Position(rows.get(end - 1).recordId(), stored.dataVersion(), false, stored.expiresAt())) : null;
        return new NativeQueryPage(rows.subList(start, end), next, manifest(pinned, scope, query.digest(), id),
                more ? "PARTIAL" : "COMPLETE", more ? "PAGE_LIMIT" : null);
    }

    private static NativeQueryPage.ReadManifest manifest(NativeDataset dataset, String scope, String digest, UUID snapshotId) {
        return new NativeQueryPage.ReadManifest(dataset.schema().id(), dataset.schema().revision(), dataset.generation(),
                dataset.dataVersion(), dataset.schema().digest(), scope, digest, snapshotId == null ? "LIVE" : "SNAPSHOT", snapshotId);
    }

    private static ArrayList<NativeRow> select(Connection connection, Prepared prepared, UUID after, int rowLimit) throws SQLException {
        var spec = prepared.spec();
        var dataset = prepared.dataset();
        var builder = new NativeQuerySql(dataset);
        String where = builder.predicate(spec.path("where"));
        String authorizedRows = prepared.access().rowPredicate(builder.parameters());
        boolean ascending = spec.at("/order/0/direction").asText().equals("ASC");
        var fields = new ArrayList<UUID>();
        spec.path("select").forEach(value -> fields.add(UUID.fromString(value.asText())));
        var columns = new LinkedHashSet<>(List.of("record_id", "record_version"));
        fields.forEach(field -> columns.addAll(NativeRowCodec.columns(dataset, field)));
        String sql = "SELECT " + columns.stream().map(NativeSqlLayout::identifier).collect(java.util.stream.Collectors.joining(","))
                + " FROM " + NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table()) + " WHERE (" + where + ") AND (" + authorizedRows + ")"
                + (after == null ? "" : ascending ? " AND record_id>?" : " AND record_id<?")
                + " ORDER BY record_id " + (ascending ? "ASC" : "DESC") + " LIMIT ?";
        var rows = new ArrayList<NativeRow>();
        try (var query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(2);
            int parameter = 1;
            for (Object value : builder.parameters()) query.setObject(parameter++, value);
            if (after != null) query.setObject(parameter++, after);
            query.setInt(parameter, rowLimit);
            try (var result = query.executeQuery()) { while (result.next()) rows.add(NativeRowCodec.read(result, dataset, fields)); }
        } catch (java.sql.SQLException failure) {
            if (failure instanceof java.sql.SQLTimeoutException || "57014".equals(failure.getSQLState())) throw new VclException("QUERY_BUDGET_EXCEEDED", "");
            throw failure;
        }
        return rows;
    }

    public static com.fasterxml.jackson.databind.JsonNode capabilities() {
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("profile", "native-live-sql-1")
                .put("order", "SYSTEM_RECORD_ID").put("maxOrderFields", 1).put("maxPageRows", 100).put("filterIndexesRequired", true);
        result.putArray("readModes").add("LIVE").add("SNAPSHOT");
        result.putObject("snapshot").put("maxRows", NativeQuerySnapshots.MAX_ROWS).put("maxBytes", NativeQuerySnapshots.MAX_BYTES)
                .put("ttlSeconds", NativeQuerySnapshots.TTL.toSeconds());
        var methods = result.putArray("methods");
        java.util.stream.Stream.of("eq", "ne", "lt", "le", "gt", "ge", "and", "or", "not", "is_null", "is_known").forEach(methods::add);
        var types = result.putArray("predicateFieldTypes");
        java.util.stream.Stream.of("BOOLEAN", "INT64", "DECIMAL", "DATE", "INSTANT", "DURATION").forEach(types::add);
        result.putArray("equalityOnlyFieldTypes").add("TEXT").add("ENUM").add("REF");
        result.put("referenceConstants", false);
        return result;
    }
}
