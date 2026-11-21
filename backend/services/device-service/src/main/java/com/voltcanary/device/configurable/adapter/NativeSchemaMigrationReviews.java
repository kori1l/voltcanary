package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.device.configurable.domain.NativeSchemaMapping;
import com.voltcanary.device.configurable.port.NativeMigrationDependencies;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Durable reservations and immutable reviews. Shadow provisioning/cutover are separate effects. */
final class NativeSchemaMigrationReviews {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final DataSource source;
    private final Clock clock;
    private final NativeMigrationDependencies dependencies;

    NativeSchemaMigrationReviews(DataSource source, Clock clock, NativeMigrationDependencies dependencies) {
        this.source = source;
        this.clock = clock;
        this.dependencies = dependencies;
    }

    JsonNode reserve(UUID account, UUID actor, UUID dataset, String key, List<String> fieldKeys) {
        require(key != null && !key.isBlank() && key.length() <= 160 && key.indexOf('\0') < 0, "IDEMPOTENCY_KEY_INVALID");
        require(fieldKeys != null && fieldKeys.size() <= 64 && new HashSet<>(fieldKeys).size() == fieldKeys.size()
                && fieldKeys.stream().allMatch(value -> value != null && value.matches("[A-Za-z][A-Za-z0-9_]{0,63}")), "FIELD_KEYS_INVALID");
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            try (var query = connection.prepareStatement("SELECT id,actor_id,dataset_id,reserved_fields_json FROM cfg_native_schema_migration WHERE account_id=? AND reservation_key=?")) {
                query.setObject(1, account);
                query.setString(2, key);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        require(row.getObject(2, UUID.class).equals(actor) && row.getObject(3, UUID.class).equals(dataset), "IDEMPOTENCY_KEY_REUSED");
                        var previous = NativeMutationValidation.PARSER.readJson(row.getString(4).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var keys = new java.util.ArrayList<String>();
                        previous.forEach(field -> keys.add(field.path("key").asText()));
                        require(keys.equals(fieldKeys), "IDEMPOTENCY_KEY_REUSED");
                        return view(load(connection, account, actor, dataset, row.getObject(1, UUID.class)));
                    }
                }
            }
            var current = NativeRowTransactions.head(connection, account, dataset, 0);
            require(current.schema().revision() < 9_007_199_254_740_991L && current.dataVersion() < 9_007_199_254_740_991L, "DATA_VERSION_LIMIT");
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cfg_native_schema_migration WHERE account_id=? AND expires_at>? AND status IN ('RESERVED','PREPARED','APPLYING','UNKNOWN')")) {
                query.setObject(1, account);
                query.setObject(2, clock.instant().atOffset(ZoneOffset.UTC));
                try (var row = query.executeQuery()) { row.next(); require(row.getLong(1) < 100, "REVIEW_QUOTA"); }
            }
            var fields = JSON.arrayNode();
            fieldKeys.forEach(field -> fields.addObject().put("key", field).put("id", UUID.randomUUID().toString()));
            UUID id = UUID.randomUUID();
            JdbcNativeDatasetCatalog.execute(connection, """
                    INSERT INTO cfg_native_schema_migration(id,account_id,actor_id,dataset_id,reservation_key,reserved_fields_json,
                        new_generation,physical_id,receipt_id,status,revision,scope_digest,expected_schema_revision,
                        expected_generation,expected_data_version,expires_at)
                    VALUES(?,?,?,?,?,?,?,?,?,'RESERVED',1,?,?,?,?,?)
                    """, id, account, actor, dataset, key, VclCanonical.encode(fields), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), scope, current.schema().revision(), current.generation(), current.dataVersion(),
                    clock.instant().plusSeconds(900).atOffset(ZoneOffset.UTC));
            return view(load(connection, account, actor, dataset, id));
        });
    }

    JsonNode prepare(UUID account, UUID actor, UUID dataset, UUID review, JsonNode request) {
        require(request != null && request.isObject() && request.size() == 3 && request.has("schema")
                && request.has("mappings") && request.has("dataMode") && request.path("dataMode").isTextual()
                && Set.of("PRESERVE", "RESET", "ARCHIVE").contains(request.path("dataMode").asText()), "REQUEST_FIELDS_INVALID");
        String canonicalRequest = VclCanonical.encode(request);
        require(canonicalRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1_048_576, "MIGRATION_PLAN_LIMIT");
        var savedHead = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> load(connection, account, actor, dataset, review));
        if (!savedHead.path("status").asText().equals("RESERVED")) {
            require(VclCanonical.sha256(canonicalRequest).equals(savedHead.path("request_digest").asText()), "REVIEW_CONFLICT");
            return view(savedHead);
        }
        var owners = dependencies.inspect(account, actor, dataset, savedHead.path("expected_schema_revision").longValue());
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = load(connection, account, actor, dataset, review);
            if (!saved.path("status").asText().equals("RESERVED")) {
                require(VclCanonical.sha256(canonicalRequest).equals(saved.path("request_digest").asText()), "REVIEW_CONFLICT");
                return view(saved);
            }
            usable(saved, scope, clock.instant());
            var previous = head(connection, account, dataset, saved);
            NativeSchema next = null;
            NativeSchemaMigrationPlans.Validation validation;
            if (request.path("dataMode").asText().equals("ARCHIVE")) {
                require(request.path("schema").isNull() && request.path("mappings").isArray() && request.path("mappings").isEmpty(), "REQUEST_FIELDS_INVALID");
                var archive = JSON.arrayNode();
                archive.addObject().put("profile", "native-schema-archive-1");
                validation = new NativeSchemaMigrationPlans.Validation(archive, JSON.arrayNode(), 0, 0);
            } else {
                next = NativeSchema.compileRevision(request.path("schema").toString(), JdbcNativeDatasetCatalog.emptyMetadata(account));
                require(next.fields().stream().noneMatch(field -> field.path("id").asText().equals(previous.recordIdFieldId().toString())), "FIELD_SCOPE_COLLISION");
                for (var field : next.fields()) {
                    if (field.at("/target/kind").asText().equals("DATASET")) {
                        UUID targetId = UUID.fromString(field.at("/target/datasetId").asText());
                        require(targetId.equals(dataset) || JdbcNativeDatasetCatalog.definition(connection, account, targetId, 0, true).isPresent(),
                                "REFERENCE_TARGET_UNAVAILABLE");
                    }
                }
                var ids = new HashSet<UUID>();
                NativeMutationValidation.PARSER.readJson(saved.path("reserved_fields_json").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .forEach(field -> ids.add(UUID.fromString(field.path("id").asText())));
                var mapping = NativeSchemaMapping.compile(previous.schema(), next, request.path("mappings"), ids, metadata(connection, previous, scope));
                var target = new NativeDataset(account, UUID.fromString(saved.path("new_generation").asText()), previous.dataVersion() + 1,
                        previous.recordIdFieldId(), next, UUID.fromString(saved.path("physical_id").asText()));
                // Compile physical index restrictions before scanning any data or sealing a review.
                new NativeSqlLayout(target);
                validation = NativeSchemaMigrationPlans.prepare(connection, previous, target, scope, review, mapping,
                        request.path("dataMode").asText().equals("RESET"));
            }
            var impact = (com.fasterxml.jackson.databind.node.ObjectNode) NativeMigrationImpact.inspect(connection, previous, next, owners, clock.instant());
            impact.put("sourceRows", validation.totalRows()).put("validRows", validation.validRows())
                    .put("dataMode", request.path("dataMode").asText());
            impact.set("errors", validation.errors());
            String plan = VclCanonical.encode(validation.plan());
            require(plan.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= NativeImportPlans.MAX_PLAN_BYTES, "MIGRATION_PLAN_LIMIT");
            String impactJson = VclCanonical.encode(impact);
            String status = validation.totalRows() == validation.validRows() && !impact.path("blocked").asBoolean() ? "PREPARED" : "FAILED";
            JdbcNativeDatasetCatalog.execute(connection, """
                    UPDATE cfg_native_schema_migration SET status=?,revision=2,canonical_request=?,request_digest=?,canonical_plan=?,plan_digest=?,
                        impact_json=?,impact_digest=?,outcome_code=? WHERE id=? AND status='RESERVED'
                    """, status, canonicalRequest, VclCanonical.sha256(canonicalRequest), plan, VclCanonical.sha256(plan),
                    impactJson, VclCanonical.sha256(impactJson), status.equals("PREPARED") ? null : "MIGRATION_REVIEW_REJECTED", review);
            var prepared = load(connection, account, actor, dataset, review);
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_native_schema_migration SET review_digest=? WHERE id=?", digest(prepared), review);
            return view(load(connection, account, actor, dataset, review));
        });
    }

    JsonNode inspect(UUID account, UUID actor, UUID dataset, UUID review) {
        return NativeRowUnitOfWork.run(source, account, actor,
                (connection, scope) -> view(load(connection, account, actor, dataset, review)));
    }

    static JsonNode load(Connection connection, UUID account, UUID actor, UUID dataset, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_native_schema_migration WHERE id=? AND account_id=? AND actor_id=? AND dataset_id=? FOR UPDATE")) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            query.setObject(4, dataset);
            try (var row = query.executeQuery()) {
                require(row.next(), "REVIEW_UNAVAILABLE");
                var saved = JSON.objectNode();
                for (String name : List.of("id", "account_id", "actor_id", "dataset_id", "reservation_key", "reserved_fields_json", "new_generation", "physical_id", "receipt_id",
                        "status", "scope_digest", "expected_generation", "canonical_request", "canonical_plan", "request_digest", "plan_digest", "impact_json", "impact_digest", "review_digest", "outcome_code", "result_json")) {
                    saved.put(name, row.getString(name));
                }
                for (String name : List.of("revision", "expected_schema_revision", "expected_data_version")) saved.put(name, row.getLong(name));
                saved.put("expires_at", row.getObject("expires_at", OffsetDateTime.class).toInstant().toString());
                return saved;
            }
        }
    }

    static JsonNode view(JsonNode saved) {
        if (!saved.path("canonical_request").isNull()) verify(saved);
        var result = JSON.objectNode();
        for (var entry : java.util.Map.of("id", "id", "datasetId", "dataset_id", "status", "status", "reviewRevision", "revision", "reviewDigest", "review_digest",
                "expiresAt", "expires_at", "generation", "new_generation", "receiptId", "receipt_id", "outcomeCode", "outcome_code").entrySet()) {
            result.set(entry.getKey(), saved.path(entry.getValue()));
        }
        result.set("idempotencyKey", saved.path("reservation_key"));
        result.set("fields", NativeMutationValidation.PARSER.readJson(saved.path("reserved_fields_json").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!saved.path("impact_json").isNull()) result.set("impact", NativeImportPlans.payload(saved.path("impact_json").asText(), false));
        if (!saved.path("result_json").isNull()) {
            var receipt = receipt(saved);
            require(VclCanonical.encode(receipt).equals(saved.path("result_json").asText()), "MIGRATION_INTEGRITY");
            result.set("receipt", receipt);
        }
        return result;
    }

    static JsonNode receipt(JsonNode saved) {
        var request = NativeImportPlans.payload(saved.path("canonical_request").asText(), false);
        var result = JSON.objectNode().put("receiptId", saved.path("receipt_id").asText())
                .put("reviewId", saved.path("id").asText()).put("datasetId", saved.path("dataset_id").asText())
                .put("reviewDigest", saved.path("review_digest").asText());
        if (request.path("dataMode").asText().equals("ARCHIVE")) {
            result.put("status", "ARCHIVED").put("schemaRevision", saved.path("expected_schema_revision").longValue())
                    .put("generation", saved.path("expected_generation").asText()).put("dataVersion", saved.path("expected_data_version").longValue())
                    .put("retainedData", true);
        } else {
            var plan = NativeImportPlans.decode(saved.path("canonical_plan").asText());
            result.put("schemaRevision", saved.path("expected_schema_revision").longValue() + 1)
                    .put("schemaDigest", plan.get(0).path("targetSchemaDigest").asText()).put("generation", saved.path("new_generation").asText())
                    .put("dataVersion", saved.path("expected_data_version").longValue() + 1)
                    .put("previousSchemaRevision", saved.path("expected_schema_revision").longValue())
                    .put("previousGeneration", saved.path("expected_generation").asText()).put("retainedPreviousGeneration", true)
                    .put("rows", plan.size() - 1).put("reset", request.path("dataMode").asText().equals("RESET"));
        }
        return result;
    }

    static String digest(JsonNode saved) {
        var envelope = JSON.objectNode().put("profile", "native-schema-migration-review-1");
        for (String field : List.of("id", "account_id", "actor_id", "dataset_id", "reservation_key", "reserved_fields_json", "new_generation", "physical_id", "receipt_id",
                "scope_digest", "expected_schema_revision", "expected_generation", "expected_data_version", "request_digest", "plan_digest", "impact_digest", "expires_at")) {
            envelope.set(field, saved.path(field));
        }
        return VclCanonical.sha256(VclCanonical.encode(envelope));
    }

    static void verify(JsonNode saved) {
        require(VclCanonical.sha256(saved.path("canonical_request").asText()).equals(saved.path("request_digest").asText())
                && VclCanonical.sha256(saved.path("canonical_plan").asText()).equals(saved.path("plan_digest").asText())
                && VclCanonical.sha256(saved.path("impact_json").asText()).equals(saved.path("impact_digest").asText())
                && digest(saved).equals(saved.path("review_digest").asText()), "MIGRATION_INTEGRITY");
    }

    static void usable(JsonNode saved, String scope, Instant now) {
        require(now.isBefore(Instant.parse(saved.path("expires_at").asText())), "REVIEW_EXPIRED");
        require(scope.equals(saved.path("scope_digest").asText()), "ACCESS_SCOPE_CHANGED");
    }

    static NativeDataset head(Connection connection, UUID account, UUID dataset, JsonNode saved) throws SQLException {
        var current = NativeRowTransactions.head(connection, account, dataset, saved.path("expected_schema_revision").longValue());
        require(current.generation().toString().equals(saved.path("expected_generation").asText())
                && current.dataVersion() == saved.path("expected_data_version").longValue(), "VALIDATION_STALE");
        return current;
    }

    private static VclMetadata metadata(Connection connection, NativeDataset current, String scope) {
        return new VclMetadata() {
            public String accessScopeDigest() { return scope; }
            public Optional<Snapshot> dataset(UUID id, long revision) {
                try {
                    return JdbcNativeDatasetCatalog.definition(connection, current.accountId(), id, revision, true)
                            .map(dataset -> new Snapshot(id, revision, dataset.metadata()));
                } catch (SQLException unavailable) { throw new VclException("NATIVE_STORAGE_UNAVAILABLE", ""); }
            }
            public Optional<Snapshot> binding(UUID id, long revision) { return Optional.empty(); }
            public boolean permits(Resource kind, UUID id) { return false; }
        };
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
