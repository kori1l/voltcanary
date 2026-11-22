package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Typed dependency observations and schema impact, without raw customer values or implicit approvals. */
final class NativeMigrationImpact {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private NativeMigrationImpact() {}

    static JsonNode inspect(
        Connection connection,
        NativeDataset previous,
        NativeSchema next,
        JsonNode owners,
        Instant now
    ) throws SQLException {
        verifyOwners(owners, previous);
        var impact = JSON.objectNode()
            .put("profile", "native-migration-impact-1")
            .put("datasetId", previous.schema().id().toString())
            .put("schemaRevision", previous.schema().revision())
            .put("schemaDigest", previous.schema().digest())
            .put("generation", previous.generation().toString())
            .put("dataVersion", previous.dataVersion());
        impact.set("owners", owners.deepCopy());
        var removed = impact.putArray("removedFieldIds");
        var added = impact.putArray("addedFieldIds");
        var changed = impact.putArray("changedFieldIds");
        var before = new java.util.LinkedHashMap<String, JsonNode>();
        previous
            .schema()
            .fields()
            .forEach(field -> before.put(field.path("id").asText(), field));
        if (next == null) {
            before.keySet().forEach(removed::add);
            impact.put("operation", "ARCHIVE");
        } else {
            impact
                .put("operation", "MIGRATE")
                .put("targetSchemaRevision", next.revision())
                .put("targetSchemaDigest", next.digest());
            next.fields().forEach(field -> {
                String id = field.path("id").asText();
                JsonNode old = before.remove(id);
                if (old == null) added.add(id);
                else if (!VclCanonical.encode(old).equals(VclCanonical.encode(field))) changed.add(
                    id
                );
            });
            before.keySet().forEach(removed::add);
            impact.put("keysChanged", !previous.schema().keys().equals(next.keys()));
            impact.put("historyChanged", previous.schema().history() != next.history());
            impact.put("indexesChanged", !previous.schema().indexes().equals(next.indexes()));
        }
        var incoming = impact.putArray("incomingReferences");
        try (
            var query = connection.prepareStatement(
                """
                SELECT d.id,d.schema_revision,f.field_id,f.definition_json FROM cfg_dataset d
                JOIN cfg_field f ON f.dataset_id=d.id AND f.schema_revision=d.schema_revision
                WHERE d.account_id=? AND d.status='READY' ORDER BY d.id,f.field_id
                """
            )
        ) {
            query.setObject(1, previous.accountId());
            int scanned = 0;
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(++scanned <= 6400, "REFERENCE_CHECK_LIMIT");
                    var field = NativeImportPlans.payload(rows.getString(4), false);
                    if (
                        !field.at("/target/kind").asText().equals("DATASET") ||
                        !field
                            .at("/target/datasetId")
                            .asText()
                            .equals(previous.schema().id().toString())
                    ) continue;
                    require(incoming.size() < 32, "REFERENCE_CHECK_LIMIT");
                    incoming
                        .addObject()
                        .put("datasetId", rows.getObject(1, UUID.class).toString())
                        .put("schemaRevision", rows.getLong(2))
                        .put("fieldId", rows.getObject(3, UUID.class).toString());
                }
            }
        }
        impact.put(
            "retainedSnapshots",
            count(
                connection,
                "SELECT COUNT(*) FROM cfg_native_query_snapshot WHERE account_id=? AND dataset_id=? AND expires_at>?",
                previous,
                now
            )
        );
        if (next == null) {
            impact.put(
                "activeHistoryLeases",
                count(
                    connection,
                    "SELECT COUNT(*) FROM cfg_native_history_lease WHERE account_id=? AND dataset_id=? AND expires_at>? AND released_at IS NULL",
                    previous,
                    now
                )
            );
        }
        impact.put(
            "preparedImports",
            count(
                connection,
                "SELECT COUNT(*) FROM cfg_import_job WHERE account_id=? AND dataset_id=? AND expires_at>? AND status='PREPARED'",
                previous,
                now
            )
        );
        long running;
        try (
            var query = connection.prepareStatement(
                """
                SELECT COUNT(*) FROM cfg_import_progress p JOIN cfg_import_job j ON j.id=p.job_id
                WHERE j.account_id=? AND j.dataset_id=? AND p.status='RUNNING'
                """
            )
        ) {
            query.setObject(1, previous.accountId());
            query.setObject(2, previous.schema().id());
            try (var rows = query.executeQuery()) {
                rows.next();
                running = rows.getLong(1);
            }
        }
        impact.put("runningImports", running);
        var shared = impact.putArray("activeNativeGrants");
        try (
            var query = connection.prepareStatement(
                "SELECT id,workspace_id,revision,policy_digest FROM cfg_native_access_grant WHERE account_id=? AND dataset_id=? AND status='ACTIVE' ORDER BY id LIMIT 1001"
            )
        ) {
            query.setObject(1, previous.accountId());
            query.setObject(2, previous.schema().id());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(shared.size() < 1000, "NATIVE_GRANT_QUOTA");
                    shared
                        .addObject()
                        .put("grantId", rows.getObject(1, UUID.class).toString())
                        .put("workspaceId", rows.getObject(2, UUID.class).toString())
                        .put("revision", rows.getLong(3))
                        .put("policyDigest", rows.getString(4));
                }
            }
        }
        boolean external = false;
        for (var owner : owners)
            for (var dependency : owner.path("dependencies"))
                external |= dependency.path("blocking").booleanValue();
        impact.put(
            "blocked",
            external ||
                running > 0 ||
                !shared.isEmpty() ||
                (next == null &&
                    (!incoming.isEmpty() ||
                        impact.path("retainedSnapshots").asLong() > 0 ||
                        impact.path("activeHistoryLeases").asLong() > 0))
        );
        return impact;
    }

    private static long count(
        Connection connection,
        String sql,
        NativeDataset dataset,
        Instant now
    ) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            query.setObject(1, dataset.accountId());
            query.setObject(2, dataset.schema().id());
            query.setObject(3, now.atOffset(ZoneOffset.UTC));
            try (var row = query.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    static void verifyOwners(JsonNode owners, NativeDataset dataset) {
        require(
            owners != null && owners.isArray() && owners.size() == 3,
            "DEPENDENCY_OWNER_UNAVAILABLE"
        );
        var seen = new HashSet<String>();
        for (var owner : owners) {
            require(
                owner.isObject() &&
                    Set.of("analytics", "alerts", "dashboards").contains(
                        owner.path("owner").asText()
                    ) &&
                    seen.add(owner.path("owner").asText()) &&
                    owner.path("complete").isBoolean() &&
                    owner.path("complete").booleanValue() &&
                    owner.path("accountId").asText().equals(dataset.accountId().toString()) &&
                    owner.path("datasetId").asText().equals(dataset.schema().id().toString()) &&
                    owner.path("schemaRevision").isIntegralNumber() &&
                    owner.path("schemaRevision").longValue() == dataset.schema().revision() &&
                    owner.path("cutDigest").asText().matches("[0-9a-f]{64}") &&
                    owner.path("dependencies").isArray() &&
                    owner.path("dependencies").size() <= 500,
                "DEPENDENCY_OWNER_UNAVAILABLE"
            );
            require(
                VclCanonical.sha256(VclCanonical.encode(owner.path("dependencies"))).equals(
                    owner.path("cutDigest").asText()
                ),
                "DEPENDENCY_OWNER_UNAVAILABLE"
            );
            for (var dependency : owner.path("dependencies")) {
                require(
                    dependency.isObject() &&
                        dependency.path("blocking").isBoolean() &&
                        dependency.path("id").isTextual() &&
                        dependency.path("kind").isTextual(),
                    "DEPENDENCY_OWNER_UNAVAILABLE"
                );
            }
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
