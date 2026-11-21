package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.device.configurable.port.NativeRowQueries;
import com.voltcanary.device.configurable.port.NativeSharedRows;
import com.voltcanary.device.configurable.port.NativeWorkspaceIdentity;
import com.voltcanary.vcl.VclDocument;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Shared reads reuse the same compiler, SQL, cursors and sealed snapshot owner. */
@RequiredArgsConstructor
public final class JdbcNativeSharedRows implements NativeSharedRows {
    private final DataSource source;
    private final NativeQueryCursor cursors;
    private final NativeWorkspaceIdentity identity;

    @Override
    public SharedTables discover(UUID workspace, UUID actor, UUID afterGrant, int limit) {
        if (limit < 1 || limit > 100) throw new com.voltcanary.vcl.VclException("QUERY_LIMIT", "");
        var member = identity.membership(workspace, actor);
        var teams = member.teams().stream().sorted().toList();
        if (teams.size() > 1000 || !workspace.equals(member.workspace()) || !actor.equals(member.actor())) {
            throw new com.voltcanary.vcl.VclException("DATA_ACCESS_DENIED", "");
        }
        var result = new java.util.ArrayList<SharedTable>();
        String teamClause = teams.isEmpty() ? "" : " OR (g.subject_kind='TEAM' AND g.subject_id IN ("
                + String.join(",", java.util.Collections.nCopies(teams.size(), "?")) + "))";
        String sql = """
                SELECT g.account_id,g.dataset_id,g.id,g.schema_revision,s.document_digest,s.canonical_document
                FROM cfg_native_access_grant g
                JOIN cfg_dataset d ON d.id=g.dataset_id AND d.account_id=g.account_id
                    AND d.schema_revision=g.schema_revision AND d.status='READY'
                JOIN cfg_schema_revision s ON s.dataset_id=d.id AND s.revision=d.schema_revision
                JOIN cfg_native_access_review r ON r.id=g.review_id AND r.status='SUCCEEDED' AND r.grant_id=g.id
                    AND r.account_id=g.account_id AND r.dataset_id=g.dataset_id AND r.workspace_id=g.workspace_id
                    AND r.actor_id=g.created_by AND r.expected_schema_revision=g.schema_revision
                JOIN accounts a ON a.id=g.account_id AND a.status='ACTIVE'
                JOIN workspace_resource_accounts w ON w.workspace_id=g.workspace_id AND w.account_id=g.account_id
                    AND w.active=TRUE AND w.consented_by=a.user_id
                WHERE g.workspace_id=? AND g.status='ACTIVE'
                  AND EXISTS (SELECT 1 FROM accounts actor WHERE actor.user_id=? AND actor.status='ACTIVE')
                  AND ((g.subject_kind='USER' AND g.subject_id=?)
                """ + teamClause + ")" + (afterGrant == null ? "" : " AND g.id>?") + " ORDER BY g.id LIMIT ?";
        try (var connection = source.getConnection(); var query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(2);
            query.setObject(1, workspace);
            query.setObject(2, actor);
            query.setObject(3, actor);
            int parameter = 4;
            for (UUID team : teams) query.setObject(parameter++, team);
            if (afterGrant != null) query.setObject(parameter++, afterGrant);
            query.setInt(parameter, limit + 1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    UUID account = rows.getObject(1, UUID.class);
                    UUID dataset = rows.getObject(2, UUID.class);
                    var schema = com.voltcanary.device.configurable.domain.NativeSchema.compileRevision(rows.getString(6),
                            JdbcNativeDatasetCatalog.emptyMetadata(account));
                    if (!schema.id().equals(dataset) || schema.revision() != rows.getLong(4) || !schema.digest().equals(rows.getString(5))) {
                        throw new com.voltcanary.vcl.VclException("SCHEMA_INTEGRITY", "");
                    }
                    result.add(new SharedTable(account, dataset, rows.getObject(3, UUID.class), schema.definition().path("label").asText()));
                }
            }
        } catch (java.sql.SQLException error) {
            throw NativeRowTransactions.failure(error);
        }
        if (!member.digest().equals(identity.membership(workspace, actor).digest())) {
            throw new com.voltcanary.vcl.VclException("ACCESS_SCOPE_CHANGED", "");
        }
        UUID next = null;
        if (result.size() > limit) {
            result.removeLast();
            next = result.getLast().grantId();
        }
        return new SharedTables(result, next);
    }

    @Override
    public JsonNode describe(NativeSharedAccess access) {
        return NativeDataTransactions.shared(source, identity, access).run(access.account(), access.actor(), (connection, scope) -> {
            var dataset = NativeRowTransactions.head(connection, access.account(), access.dataset(), 0);
            scope.policy().validate(dataset);
            var metadata = scope.metadata(dataset);
            var result = JsonNodeFactory.instance.objectNode().put("profile", "native-granted-fields-1")
                    .put("datasetId", access.dataset().toString()).put("grantId", access.grant().toString())
                    .put("schemaRevision", dataset.schema().revision()).put("generation", dataset.generation().toString())
                    .put("dataVersion", dataset.dataVersion()).put("scopeDigest", scope.digest())
                    .put("recordIdFieldId", dataset.recordIdFieldId().toString()).put("label", dataset.schema().definition().path("label").asText());
            result.set("fields", metadata.path("fields"));
            result.set("readyIndexedFields", metadata.at("/storage/indexedFieldIds"));
            boolean keysVisible = scope.policy().readFields().containsAll(dataset.schema().keys());
            result.put("keysVisible", keysVisible);
            if (keysVisible) result.set("keyFieldIds", metadata.path("keyFieldIds"));
            result.set("policy", scope.policy().json());
            result.set("queryCapabilities", JdbcNativeRowQueries.capabilities());
            return result;
        });
    }

    @Override
    public NativeQueryPage query(NativeSharedAccess access, VclDocument document) {
        return queries(access).query(access.account(), access.actor(), document);
    }

    @Override
    public NativeRowQueries.Snapshot seal(NativeSharedAccess access, VclDocument document, NativeRowQueries.AsOfProjection projection) {
        return queries(access).seal(access.account(), access.actor(), document, projection);
    }

    @Override
    public NativeQueryPage.ReadManifest inspectAsOf(NativeSharedAccess access, VclDocument document, NativeRowQueries.AsOfProjection projection) {
        return queries(access).inspectAsOf(access.account(), access.actor(), document, projection);
    }

    @Override
    public NativeRowQueries.AsOf asOf(NativeSharedAccess access, VclDocument document, UUID intervalField, Instant eventTime, Map<UUID, JsonNode> keys) {
        return queries(access).asOf(access.account(), access.actor(), document, intervalField, eventTime, keys);
    }

    @Override
    public NativeRowQueries.AsOfSplit splitAsOf(NativeSharedAccess access, VclDocument document, UUID intervalField, Instant start, Instant end, Map<UUID, JsonNode> keys) {
        return queries(access).splitAsOf(access.account(), access.actor(), document, intervalField, start, end, keys);
    }

    private JdbcNativeRowQueries queries(NativeSharedAccess access) {
        return new JdbcNativeRowQueries(cursors, NativeDataTransactions.shared(source, identity, access));
    }
}
