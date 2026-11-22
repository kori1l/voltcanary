package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.domain.NativeAccessPolicy;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.port.NativeSharing;
import com.voltcanary.device.configurable.port.NativeWorkspaceIdentity;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Reviewed owner consent for one immutable workspace/table/subject/field/row grant. */
@RequiredArgsConstructor
public final class JdbcNativeSharing implements NativeSharing {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final DataSource source;
    private final Clock clock;
    private final NativeWorkspaceIdentity identity;

    @Override
    public JsonNode prepare(
        UUID account,
        UUID actor,
        UUID dataset,
        String key,
        JsonNode definition
    ) {
        key(key);
        closed(definition, Set.of("workspaceId", "subject", "policy"));
        UUID workspace = uuid(definition.path("workspaceId"));
        closed(definition.path("subject"), Set.of("kind", "id"));
        require(
            Set.of("USER", "TEAM").contains(definition.at("/subject/kind").asText()),
            "NATIVE_SCOPE_INVALID"
        );
        uuid(definition.at("/subject/id"));
        var policy = NativeAccessPolicy.parse(definition.path("policy"));
        var normalized = (ObjectNode) definition.deepCopy();
        normalized.set("policy", policy.json());
        String canonical = VclCanonical.encode(normalized);
        var existing = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            NativeRowTransactions.head(connection, account, dataset, 0);
            try (
                var query = connection.prepareStatement(
                    "SELECT id,actor_id,dataset_id,definition_digest FROM cfg_native_access_review WHERE account_id=? AND reservation_key=?"
                )
            ) {
                query.setObject(1, account);
                query.setString(2, key);
                try (var row = query.executeQuery()) {
                    if (!row.next()) return null;
                    require(
                        actor.equals(row.getObject(2, UUID.class)) &&
                            dataset.equals(row.getObject(3, UUID.class)) &&
                            VclCanonical.sha256(canonical).equals(row.getString(4)),
                        "IDEMPOTENCY_KEY_REUSED"
                    );
                    return view(
                        load(connection, account, actor, dataset, row.getObject(1, UUID.class))
                    );
                }
            }
        });
        if (existing != null) return existing;
        String membership = subjects(actor, normalized);
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            // The account fence serializes same-key retries as well as grant quota and consent changes.
            try (
                var query = connection.prepareStatement(
                    "SELECT id,definition_digest FROM cfg_native_access_review WHERE account_id=? AND reservation_key=?"
                )
            ) {
                query.setObject(1, account);
                query.setString(2, key);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        var saved = load(
                            connection,
                            account,
                            actor,
                            dataset,
                            row.getObject(1, UUID.class)
                        );
                        require(
                            VclCanonical.sha256(canonical).equals(row.getString(2)),
                            "IDEMPOTENCY_KEY_REUSED"
                        );
                        return view(saved);
                    }
                }
            }
            var current = NativeRowTransactions.head(connection, account, dataset, 0);
            policy.validate(current);
            records(connection, current, policy);
            long association = association(connection, workspace, account, actor);
            try (
                var query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM cfg_native_access_review WHERE account_id=? AND status='PREPARED' AND expires_at>?"
                )
            ) {
                query.setObject(1, account);
                query.setObject(2, clock.instant().atOffset(ZoneOffset.UTC));
                try (var row = query.executeQuery()) {
                    row.next();
                    require(row.getLong(1) < 100, "REVIEW_QUOTA");
                }
            }
            UUID review = UUID.randomUUID();
            JdbcNativeDatasetCatalog.execute(
                connection,
                """
                INSERT INTO cfg_native_access_review(id,account_id,actor_id,dataset_id,workspace_id,grant_id,receipt_id,reservation_key,
                    scope_digest,canonical_definition,definition_digest,review_digest,status,expected_schema_revision,
                    expected_generation,expected_data_version,association_version,membership_digest,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',?,?,?,?,?,?)
                """,
                review,
                account,
                actor,
                dataset,
                workspace,
                UUID.randomUUID(),
                UUID.randomUUID(),
                key,
                scope,
                canonical,
                VclCanonical.sha256(canonical),
                "0".repeat(64),
                current.schema().revision(),
                current.generation(),
                current.dataVersion(),
                association,
                membership,
                clock.instant().plusSeconds(900).atOffset(ZoneOffset.UTC)
            );
            var saved = load(connection, account, actor, dataset, review);
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_native_access_review SET review_digest=? WHERE id=?",
                digest(saved),
                review
            );
            return view(load(connection, account, actor, dataset, review));
        });
    }

    @Override
    public JsonNode inspect(UUID account, UUID actor, UUID dataset, UUID review) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) ->
            view(load(connection, account, actor, dataset, review))
        );
    }

    @Override
    public JsonNode apply(
        UUID account,
        UUID actor,
        UUID dataset,
        UUID review,
        long revision,
        String digest,
        String key
    ) {
        var initial = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = load(connection, account, actor, dataset, review);
            confirmation(saved, revision, digest, key);
            return saved;
        });
        if (initial.path("status").asText().equals("SUCCEEDED")) return view(initial);
        var definition = NativeImportPlans.payload(
            initial.path("canonical_definition").asText(),
            false
        );
        String membership = subjects(actor, definition);
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = load(connection, account, actor, dataset, review);
            confirmation(saved, revision, digest, key);
            if (saved.path("status").asText().equals("SUCCEEDED")) return view(saved);
            require(saved.path("status").asText().equals("PREPARED"), "REVIEW_STATE_CONFLICT");
            require(
                clock
                    .instant()
                    .isBefore(java.time.Instant.parse(saved.path("expires_at").asText())),
                "REVIEW_EXPIRED"
            );
            require(
                scope.equals(saved.path("scope_digest").asText()) &&
                    membership.equals(saved.path("membership_digest").asText()),
                "ACCESS_SCOPE_CHANGED"
            );
            var current = NativeRowTransactions.head(
                connection,
                account,
                dataset,
                saved.path("expected_schema_revision").longValue()
            );
            require(
                current
                    .generation()
                    .toString()
                    .equals(saved.path("expected_generation").asText()) &&
                    current.dataVersion() == saved.path("expected_data_version").longValue(),
                "VALIDATION_STALE"
            );
            UUID workspace = UUID.fromString(saved.path("workspace_id").asText());
            require(
                association(connection, workspace, account, actor) ==
                    saved.path("association_version").longValue(),
                "ACCESS_SCOPE_CHANGED"
            );
            var policy = NativeAccessPolicy.parse(definition.path("policy"));
            policy.validate(current);
            records(connection, current, policy);
            try (
                var query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM cfg_native_access_grant WHERE account_id=? AND status='ACTIVE'"
                )
            ) {
                query.setObject(1, account);
                try (var row = query.executeQuery()) {
                    row.next();
                    require(row.getLong(1) < 1000, "NATIVE_GRANT_QUOTA");
                }
            }
            String canonical = VclCanonical.encode(policy.json());
            require(
                clock
                    .instant()
                    .isBefore(java.time.Instant.parse(saved.path("expires_at").asText())),
                "REVIEW_EXPIRED"
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                """
                INSERT INTO cfg_native_access_grant(id,account_id,workspace_id,dataset_id,schema_revision,subject_kind,subject_id,
                    canonical_policy,policy_digest,revision,status,review_id,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,1,'ACTIVE',?,?)
                """,
                UUID.fromString(saved.path("grant_id").asText()),
                account,
                workspace,
                dataset,
                current.schema().revision(),
                definition.at("/subject/kind").asText(),
                uuid(definition.at("/subject/id")),
                canonical,
                VclCanonical.sha256(canonical),
                review,
                actor
            );
            String receipt = VclCanonical.encode(receipt(saved));
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_native_access_review SET status='SUCCEEDED',result_json=? WHERE id=?",
                receipt,
                review
            );
            changed(connection, saved, actor, "NATIVE_ACCESS_GRANTED", receipt);
            return view(load(connection, account, actor, dataset, review));
        });
    }

    @Override
    public JsonNode list(UUID account, UUID actor, UUID dataset) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            NativeRowTransactions.head(connection, account, dataset, 0);
            var result = JSON.arrayNode();
            try (
                var query = connection.prepareStatement(
                    "SELECT id,workspace_id,schema_revision,subject_kind,subject_id,canonical_policy,policy_digest,revision FROM cfg_native_access_grant WHERE account_id=? AND dataset_id=? AND status='ACTIVE' ORDER BY id LIMIT 1001"
                )
            ) {
                query.setObject(1, account);
                query.setObject(2, dataset);
                try (var row = query.executeQuery()) {
                    while (row.next()) {
                        require(result.size() < 1000, "NATIVE_GRANT_QUOTA");
                        String policy = row.getString(6);
                        require(
                            VclCanonical.sha256(policy).equals(row.getString(7)),
                            "NATIVE_GRANT_INTEGRITY"
                        );
                        var item = result
                            .addObject()
                            .put("grantId", row.getObject(1, UUID.class).toString())
                            .put("workspaceId", row.getObject(2, UUID.class).toString())
                            .put("schemaRevision", row.getLong(3))
                            .put("subjectKind", row.getString(4))
                            .put("subjectId", row.getObject(5, UUID.class).toString())
                            .put("revision", row.getLong(8));
                        item.set(
                            "policy",
                            NativeAccessPolicy.parse(
                                NativeImportPlans.payload(policy, false)
                            ).json()
                        );
                    }
                }
            }
            return result;
        });
    }

    @Override
    public JsonNode revoke(
        UUID account,
        UUID actor,
        UUID dataset,
        UUID grant,
        long expectedRevision,
        String key
    ) {
        key(key);
        require(expectedRevision == 1, "REVIEW_CONFLICT");
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            UUID workspace;
            try (
                var query = connection.prepareStatement(
                    "SELECT workspace_id,status,revision,revoked_key,revoked_result FROM cfg_native_access_grant WHERE id=? AND account_id=? AND dataset_id=? FOR UPDATE"
                )
            ) {
                query.setObject(1, grant);
                query.setObject(2, account);
                query.setObject(3, dataset);
                try (var row = query.executeQuery()) {
                    require(row.next(), "NATIVE_GRANT_UNAVAILABLE");
                    if (row.getString(2).equals("REVOKED")) {
                        require(
                            key.equals(row.getString(4)) && row.getLong(3) == 2,
                            "IDEMPOTENCY_KEY_REUSED"
                        );
                        return NativeImportPlans.payload(row.getString(5), false);
                    }
                    require(row.getLong(3) == expectedRevision, "REVIEW_CONFLICT");
                    workspace = row.getObject(1, UUID.class);
                }
            }
            UUID receiptId = UUID.nameUUIDFromBytes(
                ("NATIVE_ACCESS_REVOKED/1:" + grant).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                )
            );
            var result = JSON.objectNode()
                .put("grantId", grant.toString())
                .put("revision", 2)
                .put("status", "REVOKED")
                .put("receiptId", receiptId.toString());
            String canonical = VclCanonical.encode(result);
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_native_access_grant SET status='REVOKED',revision=2,revoked_key=?,revoked_result=? WHERE id=?",
                key,
                canonical,
                grant
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE workspace_resource_accounts SET version=version+1 WHERE workspace_id=? AND account_id=?",
                workspace,
                account
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_ACCESS_REVOKED',?)",
                receiptId,
                account,
                dataset,
                canonical
            );
            return result;
        });
    }

    private String subjects(UUID actor, JsonNode definition) {
        UUID workspace = uuid(definition.path("workspaceId"));
        String owner = identity.membership(workspace, actor).digest();
        UUID subject = uuid(definition.at("/subject/id"));
        String target;
        if (definition.at("/subject/kind").asText().equals("USER")) target = identity
            .membership(workspace, subject)
            .digest();
        else {
            require(identity.teamExists(workspace, subject), "NATIVE_SCOPE_INVALID");
            target = "TEAM:" + subject;
        }
        require(
            owner.equals(identity.membership(workspace, actor).digest()),
            "ACCESS_SCOPE_CHANGED"
        );
        return VclCanonical.sha256(owner + ":" + target);
    }

    private static long association(
        Connection connection,
        UUID workspace,
        UUID account,
        UUID actor
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT version,consented_by FROM workspace_resource_accounts WHERE workspace_id=? AND account_id=? AND active=TRUE FOR UPDATE"
            )
        ) {
            query.setObject(1, workspace);
            query.setObject(2, account);
            try (var row = query.executeQuery()) {
                require(
                    row.next() && actor.equals(row.getObject(2, UUID.class)),
                    "DATA_ACCESS_DENIED"
                );
                return row.getLong(1);
            }
        }
    }

    private static void records(
        Connection connection,
        NativeDataset dataset,
        NativeAccessPolicy policy
    ) throws SQLException {
        if (policy.allRows() || policy.recordIds().isEmpty()) return;
        try (
            var query = connection.prepareStatement(
                "SELECT COUNT(*) FROM " +
                    NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table()) +
                    " WHERE record_id IN (" +
                    String.join(
                        ",",
                        java.util.Collections.nCopies(policy.recordIds().size(), "?")
                    ) +
                    ")"
            )
        ) {
            int parameter = 1;
            for (UUID id : policy.recordIds()) query.setObject(parameter++, id);
            try (var row = query.executeQuery()) {
                row.next();
                require(row.getLong(1) == policy.recordIds().size(), "ROW_UNAVAILABLE");
            }
        }
    }

    static JsonNode load(
        Connection connection,
        UUID account,
        UUID actor,
        UUID dataset,
        UUID review
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM cfg_native_access_review WHERE id=? AND account_id=? AND actor_id=? AND dataset_id=? FOR UPDATE"
            )
        ) {
            query.setObject(1, review);
            query.setObject(2, account);
            query.setObject(3, actor);
            query.setObject(4, dataset);
            try (var row = query.executeQuery()) {
                require(row.next(), "REVIEW_UNAVAILABLE");
                var result = JSON.objectNode();
                for (String field : List.of(
                    "id",
                    "account_id",
                    "actor_id",
                    "dataset_id",
                    "workspace_id",
                    "grant_id",
                    "receipt_id",
                    "reservation_key",
                    "scope_digest",
                    "canonical_definition",
                    "definition_digest",
                    "review_digest",
                    "status",
                    "expected_generation",
                    "membership_digest",
                    "result_json"
                ))
                    result.put(field, row.getString(field));
                for (String field : List.of(
                    "expected_schema_revision",
                    "expected_data_version",
                    "association_version"
                ))
                    result.put(field, row.getLong(field));
                result.put(
                    "expires_at",
                    row.getObject("expires_at", OffsetDateTime.class).toInstant().toString()
                );
                return result;
            }
        }
    }

    private static String digest(JsonNode saved) {
        var envelope = JSON.objectNode().put("profile", "native-access-review-1");
        for (String field : List.of(
            "id",
            "account_id",
            "actor_id",
            "dataset_id",
            "workspace_id",
            "grant_id",
            "receipt_id",
            "reservation_key",
            "scope_digest",
            "definition_digest",
            "expected_schema_revision",
            "expected_generation",
            "expected_data_version",
            "association_version",
            "membership_digest",
            "expires_at"
        ))
            envelope.set(field, saved.path(field));
        return VclCanonical.sha256(VclCanonical.encode(envelope));
    }

    static void verify(JsonNode saved) {
        require(
            VclCanonical.sha256(saved.path("canonical_definition").asText()).equals(
                saved.path("definition_digest").asText()
            ) && digest(saved).equals(saved.path("review_digest").asText()),
            "NATIVE_GRANT_INTEGRITY"
        );
    }

    private static void confirmation(JsonNode saved, long revision, String digest, String key) {
        verify(saved);
        require(
            revision == 1 && digest != null && digest.equals(saved.path("review_digest").asText()),
            "REVIEW_CONFLICT"
        );
        require(
            key != null && key.equals(saved.path("reservation_key").asText()),
            "IDEMPOTENCY_KEY_REUSED"
        );
    }

    private static JsonNode receipt(JsonNode saved) {
        return JSON.objectNode()
            .put("receiptId", saved.path("receipt_id").asText())
            .put("reviewId", saved.path("id").asText())
            .put("grantId", saved.path("grant_id").asText())
            .put("datasetId", saved.path("dataset_id").asText())
            .put("workspaceId", saved.path("workspace_id").asText())
            .put("revision", 1)
            .put("reviewDigest", saved.path("review_digest").asText());
    }

    private static JsonNode view(JsonNode saved) {
        verify(saved);
        var result = JSON.objectNode()
            .put("reviewId", saved.path("id").asText())
            .put("grantId", saved.path("grant_id").asText())
            .put("reviewRevision", 1)
            .put("reviewDigest", saved.path("review_digest").asText())
            .put("status", saved.path("status").asText())
            .put("expiresAt", saved.path("expires_at").asText())
            .put("schemaRevision", saved.path("expected_schema_revision").longValue());
        result.set("idempotencyKey", saved.path("reservation_key"));
        result.set(
            "definition",
            NativeImportPlans.payload(saved.path("canonical_definition").asText(), false)
        );
        if (!saved.path("result_json").isNull()) {
            require(
                saved
                    .path("result_json")
                    .asText()
                    .equals(VclCanonical.encode(receipt(saved))),
                "NATIVE_GRANT_INTEGRITY"
            );
            result.set("receipt", receipt(saved));
        }
        return result;
    }

    private static void changed(
        Connection connection,
        JsonNode saved,
        UUID actor,
        String event,
        String payload
    ) throws SQLException {
        UUID workspace = UUID.fromString(saved.path("workspace_id").asText());
        UUID account = UUID.fromString(saved.path("account_id").asText());
        JdbcNativeDatasetCatalog.execute(
            connection,
            "UPDATE workspace_resource_accounts SET version=version+1 WHERE workspace_id=? AND account_id=?",
            workspace,
            account
        );
        JdbcNativeDatasetCatalog.execute(
            connection,
            "INSERT INTO workspace_resource_audit(id,workspace_id,account_id,actor_id,action,subject_id) VALUES(?,?,?,?,?,?)",
            UUID.fromString(saved.path("receipt_id").asText()),
            workspace,
            account,
            actor,
            event,
            UUID.fromString(saved.path("grant_id").asText())
        );
        JdbcNativeDatasetCatalog.execute(
            connection,
            "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,?,?)",
            UUID.fromString(saved.path("receipt_id").asText()),
            account,
            UUID.fromString(saved.path("dataset_id").asText()),
            event,
            payload
        );
    }

    private static void key(String value) {
        require(
            value != null && !value.isBlank() && value.length() <= 160 && value.indexOf('\0') < 0,
            "IDEMPOTENCY_KEY_INVALID"
        );
    }

    private static UUID uuid(JsonNode value) {
        try {
            require(value.isTextual(), "NATIVE_SCOPE_INVALID");
            UUID id = UUID.fromString(value.textValue());
            require(id.toString().equals(value.textValue()), "NATIVE_SCOPE_INVALID");
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new VclException("NATIVE_SCOPE_INVALID", "");
        }
    }

    private static void closed(JsonNode value, Set<String> fields) {
        require(
            value != null &&
                value.isObject() &&
                value.size() == fields.size() &&
                fields.stream().allMatch(value::has),
            "REQUEST_FIELDS_INVALID"
        );
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
