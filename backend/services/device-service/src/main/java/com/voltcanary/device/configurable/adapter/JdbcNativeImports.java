package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeImport;
import com.voltcanary.device.configurable.port.NativeImports;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** Bounded owner-local import: immutable artifact/manifest, current authority, atomic data/receipt/outbox. */
@RequiredArgsConstructor
public final class JdbcNativeImports implements NativeImports {

    private static final VclParser PARSER = new VclParser();
    private static final VclCompiler COMPILER = new VclCompiler(
        new VclRegistry(PARSER),
        VclUnits.standard()
    );
    private final DataSource source;
    private final Clock clock;

    @Override
    public Artifact upload(UUID account, UUID actor, String key, byte[] bytes) {
        require(bytes != null && bytes.length <= 1_048_576, "LIMIT_EXCEEDED");
        key(key);
        byte[] content = bytes.clone();
        String hash = sha(content);
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            JdbcNativeDatasetCatalog.execute(
                connection,
                "DELETE FROM cfg_import_artifact WHERE account_id=? AND expires_at<=?",
                account,
                time(clock.instant())
            );
            try (
                var query = connection.prepareStatement(
                    "SELECT * FROM cfg_import_artifact WHERE account_id=? AND idempotency_key=?"
                )
            ) {
                query.setObject(1, account);
                query.setString(2, key);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        require(
                            actor.equals(rows.getObject("actor_id", UUID.class)) &&
                                hash.equals(rows.getString("sha256")),
                            "IDEMPOTENCY_KEY_REUSED"
                        );
                        return artifact(rows);
                    }
                }
            }
            try (
                var query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM cfg_import_artifact WHERE account_id=?"
                )
            ) {
                query.setObject(1, account);
                requireQuota(query);
            }
            UUID id = UUID.randomUUID();
            Instant expiry = clock
                .instant()
                .plus(Duration.ofMinutes(30))
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            JdbcNativeDatasetCatalog.execute(
                connection,
                "INSERT INTO cfg_import_artifact(id,account_id,actor_id,idempotency_key,sha256,byte_count,content_base64,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                id,
                account,
                actor,
                key,
                hash,
                content.length,
                Base64.getEncoder().encodeToString(content),
                time(expiry)
            );
            return new Artifact(id, hash, content.length, expiry);
        });
    }

    @Override
    public NativeImport validate(
        UUID account,
        UUID actor,
        UUID datasetId,
        String key,
        VclDocument document
    ) {
        key(key);
        require(document.kind() == VclDocument.Kind.IMPORT, "IMPORT_REQUIRED");
        var spec = document.spec();
        require(
            spec.at("/target/datasetId").asText().equals(datasetId.toString()),
            "TARGET_CONFLICT"
        );
        require(
            Set.of("ATOMIC_INSERT", "ATOMIC_UPSERT", "STAGED_REPLACE", "CHUNKED_UPSERT").contains(
                spec.path("mode").asText()
            ),
            "UNSUPPORTED_IMPORT_MODE"
        );
        int rowLimit = spec.path("mode").asText().equals("CHUNKED_UPSERT") ? 10_000 : 500;
        require(
            spec.at("/limits/maxRows").longValue() <= rowLimit &&
                spec.at("/limits/maxBytes").longValue() <= 1_048_576,
            "LIMIT_EXCEEDED"
        );
        String supplied = VclCanonical.encode(document.json());
        String suppliedDigest = VclCanonical.sha256(supplied);
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            try (
                var query = connection.prepareStatement(
                    "SELECT id,actor_id,document_digest FROM cfg_import_job WHERE account_id=? AND idempotency_key=?"
                )
            ) {
                query.setObject(1, account);
                query.setString(2, key);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        require(
                            actor.equals(rows.getObject("actor_id", UUID.class)) &&
                                suppliedDigest.equals(rows.getString("document_digest")),
                            "IDEMPOTENCY_KEY_REUSED"
                        );
                        return view(
                            connection,
                            account,
                            actor,
                            datasetId,
                            rows.getObject("id", UUID.class)
                        );
                    }
                }
            }
            requireJobCapacity(connection, account, clock.instant());
            var dataset = NativeRowTransactions.head(
                connection,
                account,
                datasetId,
                spec.at("/target/schemaRevision").longValue()
            );
            UUID artifactId = UUID.fromString(spec.path("artifactId").asText());
            byte[] bytes = readArtifact(
                connection,
                account,
                actor,
                artifactId,
                spec.path("artifactSha256").asText()
            );
            var owners = NativeImportReferences.owners(connection, dataset, spec);
            COMPILER.compile(document, metadata(dataset, scope, artifactId, sha(bytes), owners));
            UUID id = UUID.randomUUID();
            var validation = NativeImportPlans.validate(
                connection,
                dataset,
                scope,
                id,
                document,
                bytes,
                owners
            );
            String plan = VclCanonical.encode(validation.rows());
            require(
                plan.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <=
                    NativeImportPlans.MAX_PLAN_BYTES,
                "LIMIT_EXCEEDED"
            );
            String planDigest = VclCanonical.sha256(plan);
            Instant expires = clock
                .instant()
                .plus(Duration.ofMinutes(15))
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            String review = NativeImportIntegrity.digest(
                id,
                account,
                actor,
                suppliedDigest,
                scope,
                planDigest,
                expires,
                validation.total(),
                validation.valid(),
                VclCanonical.encode(validation.errors())
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                """
                INSERT INTO cfg_import_job(id,account_id,actor_id,dataset_id,idempotency_key,scope_digest,document_digest,
                canonical_document,review_digest,canonical_plan,plan_digest,total_rows,valid_rows,errors_json,status,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id,
                account,
                actor,
                datasetId,
                key,
                scope,
                suppliedDigest,
                supplied,
                review,
                plan,
                planDigest,
                validation.total(),
                validation.valid(),
                VclCanonical.encode(validation.errors()),
                validation.approvable() ? "PREPARED" : "REJECTED",
                time(expires)
            );
            if (spec.path("mode").asText().equals("STAGED_REPLACE") && validation.approvable()) {
                var stage = validation.rows().get(0);
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    """
                    INSERT INTO cfg_native_generation(dataset_id,generation,physical_id,account_id,schema_revision,import_job_id,status)
                    VALUES(?,?,?,?,?,?,'PROVISIONING')
                    """,
                    datasetId,
                    UUID.fromString(stage.path("generation").asText()),
                    UUID.fromString(stage.path("physicalId").asText()),
                    account,
                    dataset.schema().revision(),
                    id
                );
            }
            return view(connection, account, actor, datasetId, id);
        });
    }

    @Override
    public NativeImport inspect(UUID account, UUID actor, UUID dataset, UUID id) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) ->
            view(connection, account, actor, dataset, id)
        );
    }

    @Override
    public NativeImport apply(
        UUID account,
        UUID actor,
        UUID datasetId,
        UUID id,
        String digest,
        String key
    ) {
        key(key);
        NativeImportStages.provision(source, clock, account, actor, datasetId, id, digest, key);
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = job(connection, account, actor, datasetId, id);
            NativeImportIntegrity.verify(id, account, actor, saved);
            require(saved.path("review_digest").asText().equals(digest), "REVIEW_CONFLICT");
            require(saved.path("idempotency_key").asText().equals(key), "IDEMPOTENCY_KEY_REUSED");
            if (NativeImportChunks.progress(connection, id) != null) return view(
                connection,
                account,
                actor,
                datasetId,
                id
            );
            if (saved.path("status").asText().equals("SUCCEEDED")) return view(
                connection,
                account,
                actor,
                datasetId,
                id
            );
            require(saved.path("status").asText().equals("PREPARED"), "REVIEW_STATE_CONFLICT");
            require(
                clock.instant().isBefore(Instant.parse(saved.path("expires_at").asText())),
                "REVIEW_EXPIRED"
            );
            require(scope.equals(saved.path("scope_digest").asText()), "ACCESS_SCOPE_CHANGED");
            String canonical = saved.path("canonical_document").asText(),
                plan = saved.path("canonical_plan").asText();
            require(
                VclCanonical.sha256(canonical).equals(saved.path("document_digest").asText()) &&
                    VclCanonical.sha256(plan).equals(saved.path("plan_digest").asText()),
                "IMPORT_INTEGRITY"
            );
            var document = PARSER.parse(canonical);
            var spec = document.spec();
            var dataset = NativeRowTransactions.head(
                connection,
                account,
                datasetId,
                spec.at("/target/schemaRevision").longValue()
            );
            require(
                dataset
                    .generation()
                    .toString()
                    .equals(spec.at("/targetPrecondition/generation").asText()) &&
                    dataset.dataVersion() == spec.at("/targetPrecondition/dataVersion").longValue(),
                "VALIDATION_STALE"
            );
            var rows = NativeImportPlans.decode(plan);
            require(!NativeImportPlans.incomplete(rows), "REVIEW_STATE_CONFLICT");
            if (spec.path("mode").asText().equals("CHUNKED_UPSERT")) {
                NativeImportChunks.start(connection, dataset, actor, id);
                return view(connection, account, actor, datasetId, id);
            }
            NativeImportPlans.recheck(connection, dataset, scope, rows);
            if (spec.path("mode").asText().equals("STAGED_REPLACE")) {
                NativeImportStages.cutover(connection, dataset, id, rows);
                return view(connection, account, actor, datasetId, id);
            }
            var result = JsonNodeFactory.instance
                .objectNode()
                .put("jobId", id.toString())
                .put("datasetId", datasetId.toString())
                .put("generation", dataset.generation().toString());
            var receipts = result.putArray("rows");
            for (var row : rows) {
                if (!row.has("document")) continue;
                var mutation = PARSER.parse(row.path("document").asText());
                var rowSpec = mutation.spec();
                if (row.has("matchedRowVersion")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) rowSpec)
                        .put("operation", "UPDATE")
                        .set("expectedRowVersion", row.path("matchedRowVersion"));
                }
                var changed = NativeRowDml.mutate(
                    connection,
                    dataset,
                    rowSpec,
                    UUID.fromString(row.path("recordId").asText()),
                    false
                );
                receipts
                    .addObject()
                    .put("recordId", changed.recordId().toString())
                    .put("recordVersion", Long.toString(changed.recordVersion()));
            }
            // A batch has one head version, irrespective of how many row mutations it contains.
            NativeRowDml.advanceHead(connection, dataset);
            long next = dataset.dataVersion() + 1;
            result.put("dataVersion", next);
            String receipt = VclCanonical.encode(result);
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_import_job SET status='SUCCEEDED',result_json=? WHERE id=?",
                receipt,
                id
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_ROWS_IMPORTED',?)",
                id,
                account,
                datasetId,
                receipt
            );
            return view(connection, account, actor, datasetId, id);
        });
    }

    @Override
    public NativeImport inspectJob(UUID account, UUID actor, UUID id) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) ->
            view(connection, account, actor, jobDataset(connection, account, actor, id), id)
        );
    }

    @Override
    public NativeImport advance(UUID account, UUID actor, UUID id, long expectedRevision) {
        NativeImportChunks.advance(source, clock, account, actor, id, expectedRevision);
        return inspectJob(account, actor, id);
    }

    @Override
    public NativeImport cancel(UUID account, UUID actor, UUID id) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            UUID dataset = jobDataset(connection, account, actor, id);
            var saved = job(connection, account, actor, dataset, id);
            NativeImportIntegrity.verify(id, account, actor, saved);
            if (saved.path("status").asText().equals("SUCCEEDED")) return view(
                connection,
                account,
                actor,
                dataset,
                id
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_import_progress SET status='CANCELLED',revision=revision+1,outcome_code='CANCELLED_BY_ACTOR',updated_at=CURRENT_TIMESTAMP WHERE job_id=? AND status IN ('RUNNING','PARTIAL','FAILED')",
                id
            );
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_import_job SET status='CANCELLED' WHERE id=? AND status='PREPARED'",
                id
            );
            return view(connection, account, actor, dataset, id);
        });
    }

    static UUID jobDataset(
        Connection connection,
        UUID account,
        UUID actor,
        UUID id
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT dataset_id FROM cfg_import_job WHERE id=? AND account_id=? AND actor_id=?"
            )
        ) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "IMPORT_UNAVAILABLE");
                return rows.getObject(1, UUID.class);
            }
        }
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode chunks(
        UUID account,
        UUID actor,
        UUID id,
        int afterChunk,
        int limit
    ) {
        require(
            afterChunk >= -1 && afterChunk <= 10_000 && limit >= 1 && limit <= 20,
            "QUERY_LIMIT"
        );
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            UUID dataset = jobDataset(connection, account, actor, id);
            var saved = job(connection, account, actor, dataset, id);
            NativeImportIntegrity.verify(id, account, actor, saved);
            var response = JsonNodeFactory.instance.objectNode().put("jobId", id.toString());
            var chunks = response.putArray("chunks");
            int next = afterChunk;
            boolean more = false;
            try (
                var query = connection.prepareStatement(
                    "SELECT chunk_index,payload_digest,result_json FROM cfg_import_chunk WHERE job_id=? AND chunk_index>? ORDER BY chunk_index LIMIT ?"
                )
            ) {
                query.setObject(1, id);
                query.setInt(2, afterChunk);
                query.setInt(3, limit + 1);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (chunks.size() == limit) {
                            more = true;
                            break;
                        }
                        String bytes = rows.getString("result_json");
                        require(
                            VclCanonical.sha256(bytes).equals(rows.getString("payload_digest")),
                            "IMPORT_INTEGRITY"
                        );
                        chunks.add(NativeImportChunks.result(bytes));
                        next = rows.getInt("chunk_index");
                    }
                }
            }
            return response.put("nextAfter", next).put("hasMore", more);
        });
    }

    private byte[] readArtifact(
        Connection connection,
        UUID account,
        UUID actor,
        UUID id,
        String hash
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM cfg_import_artifact WHERE id=? AND account_id=? AND actor_id=?"
            )
        ) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "ARTIFACT_UNAVAILABLE");
                require(
                    clock
                        .instant()
                        .isBefore(rows.getObject("expires_at", OffsetDateTime.class).toInstant()),
                    "ARTIFACT_EXPIRED"
                );
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(rows.getString("content_base64"));
                } catch (IllegalArgumentException invalid) {
                    throw new VclException("ARTIFACT_INTEGRITY", "");
                }
                require(
                    bytes.length == rows.getInt("byte_count") &&
                        sha(bytes).equals(hash) &&
                        hash.equals(rows.getString("sha256")),
                    "ARTIFACT_INTEGRITY"
                );
                return bytes;
            }
        }
    }

    private static VclMetadata metadata(
        NativeDataset dataset,
        String scope,
        UUID artifact,
        String hash,
        java.util.Map<UUID, NativeDataset> owners
    ) {
        return new VclMetadata() {
            public String accessScopeDigest() {
                return scope;
            }

            public Optional<Snapshot> dataset(UUID id, long revision) {
                var owner = id.equals(dataset.schema().id()) ? dataset : owners.get(id);
                return owner != null && revision == owner.schema().revision()
                    ? Optional.of(new Snapshot(id, revision, owner.metadata()))
                    : Optional.empty();
            }

            public Optional<Snapshot> binding(UUID id, long revision) {
                return Optional.empty();
            }

            public boolean permits(Resource resource, UUID id) {
                return false;
            }

            public Optional<String> artifactSha256(UUID id) {
                return artifact.equals(id) ? Optional.of(hash) : Optional.empty();
            }
        };
    }

    static JsonNode job(
        Connection connection,
        UUID account,
        UUID actor,
        UUID dataset,
        UUID id
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                "SELECT * FROM cfg_import_job WHERE id=? AND account_id=? AND actor_id=? AND dataset_id=? FOR UPDATE"
            )
        ) {
            query.setObject(1, id);
            query.setObject(2, account);
            query.setObject(3, actor);
            query.setObject(4, dataset);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "IMPORT_UNAVAILABLE");
                var saved = JsonNodeFactory.instance.objectNode();
                for (String field : Set.of(
                    "idempotency_key",
                    "scope_digest",
                    "document_digest",
                    "canonical_document",
                    "review_digest",
                    "canonical_plan",
                    "plan_digest",
                    "status",
                    "errors_json",
                    "result_json"
                ))
                    saved.put(field, rows.getString(field));
                saved.put(
                    "expires_at",
                    rows.getObject("expires_at", OffsetDateTime.class).toInstant().toString()
                );
                saved
                    .put("total_rows", rows.getInt("total_rows"))
                    .put("valid_rows", rows.getInt("valid_rows"));
                return saved;
            }
        }
    }

    static NativeImport view(
        Connection connection,
        UUID account,
        UUID actor,
        UUID dataset,
        UUID id
    ) throws SQLException {
        var saved = job(connection, account, actor, dataset, id);
        NativeImportIntegrity.verify(id, account, actor, saved);
        int total = saved.path("total_rows").intValue(),
            valid = saved.path("valid_rows").intValue();
        require(
            VclCanonical.sha256(saved.path("canonical_document").asText()).equals(
                saved.path("document_digest").asText()
            ) &&
                VclCanonical.sha256(saved.path("canonical_plan").asText()).equals(
                    saved.path("plan_digest").asText()
                ),
            "IMPORT_INTEGRITY"
        );
        var document = PARSER.parse(saved.path("canonical_document").asText());
        var plan = NativeImportPlans.decode(saved.path("canonical_plan").asText());
        boolean complete = !NativeImportPlans.incomplete(plan);
        int inserts = 0,
            updates = 0;
        for (var row : plan) {
            if (!row.has("document")) continue;
            if (
                !row.has("matchedRowVersion") &&
                NativeImportPlans.payload(row.path("document").asText(), false)
                    .at("/spec/operation")
                    .asText()
                    .equals("INSERT")
            ) inserts++;
            else updates++;
        }
        var diff = JsonNodeFactory.instance
            .objectNode()
            .put("profile", "native-atomic-import-1")
            .put("mode", document.spec().path("mode").asText())
            .put("inserts", inserts)
            .put("updates", updates)
            .put("deletes", 0);
        diff.set("target", document.spec().path("target"));
        diff.set("targetPrecondition", document.spec().path("targetPrecondition"));
        diff.set("artifactId", document.spec().path("artifactId"));
        diff.set("artifactSha256", document.spec().path("artifactSha256"));
        diff.set("mappingRevision", document.spec().path("mappingRevision"));
        var mapping = diff.putObject("mapping");
        mapping.set("format", document.spec().path("format"));
        mapping.set("columns", document.spec().path("columns"));
        if (!plan.isEmpty() && plan.get(0).has("references")) {
            var readSet = diff.putArray("referenceReadSet");
            for (var pin : plan.get(0).path("references")) {
                var summary = pin.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) summary).remove("records");
                ((com.fasterxml.jackson.databind.node.ObjectNode) summary).put(
                    "resolvedRecords",
                    pin.path("records").size()
                );
                readSet.add(summary);
            }
        }
        if (
            document.spec().path("mode").asText().equals("STAGED_REPLACE") &&
            !plan.isEmpty() &&
            plan.get(0).has("removed")
        ) {
            diff.put("profile", "native-generation-replace-1").put(
                "deletes",
                plan.get(0).path("removed").size()
            );
            diff.set("removed", plan.get(0).path("removed"));
            diff.set("generation", plan.get(0).path("generation"));
        }
        var progress = NativeImportChunks.progress(connection, id);
        diff.put("idempotencyKey", saved.path("idempotency_key").asText());
        String status =
            progress == null ? saved.path("status").asText() : progress.path("status").asText();
        require(complete || ("REJECTED".equals(status) && progress == null), "IMPORT_INTEGRITY");
        return new NativeImport(
            id,
            dataset,
            status,
            Instant.parse(saved.path("expires_at").asText()),
            saved.path("review_digest").asText(),
            complete ? total : null,
            total,
            complete ? 0 : null,
            valid,
            total - valid,
            complete,
            total - valid > 100,
            PARSER.readJson(
                saved.path("errors_json").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ),
            saved.path("result_json").isNull()
                ? null
                : NativeImportChunks.result(saved.path("result_json").asText()),
            diff,
            progress,
            !complete
                ? plan.path(0).path("reason").asText()
                : progress == null || progress.path("outcomeCode").isNull()
                  ? null
                  : progress.path("outcomeCode").asText()
        );
    }

    private static Artifact artifact(ResultSet rows) throws SQLException {
        return new Artifact(
            rows.getObject("id", UUID.class),
            rows.getString("sha256"),
            rows.getInt("byte_count"),
            rows.getObject("expires_at", OffsetDateTime.class).toInstant()
        );
    }

    private static void requireJobCapacity(
        Connection connection,
        UUID account,
        Instant now
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                """
                SELECT COUNT(*) FROM (
                    SELECT 1 FROM cfg_import_job j
                    LEFT JOIN cfg_import_progress p ON p.job_id=j.id
                    WHERE j.account_id=? AND (p.status='RUNNING'
                        OR (p.job_id IS NULL AND j.status='PREPARED' AND j.expires_at>?))
                    LIMIT 100
                ) active_jobs
                """
            )
        ) {
            query.setObject(1, account);
            query.setObject(2, time(now));
            requireQuota(query);
        }
    }

    private static void requireQuota(java.sql.PreparedStatement query) throws SQLException {
        try (var rows = query.executeQuery()) {
            rows.next();
            require(rows.getLong(1) < 100, "IMPORT_QUOTA");
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void key(String key) {
        require(
            key != null &&
                !key.isBlank() &&
                key.codePointCount(0, key.length()) <= 160 &&
                key.indexOf('\0') < 0,
            "IDEMPOTENCY_KEY_INVALID"
        );
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
