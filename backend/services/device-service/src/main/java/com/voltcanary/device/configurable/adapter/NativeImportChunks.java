package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** Durable bounded chunk transitions: each local data/receipt/outbox/progress commit is atomic. */
final class NativeImportChunks {
    private static final VclParser PARSER = new VclParser();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private NativeImportChunks() { }

    static void start(Connection connection, NativeDataset dataset, UUID actor, UUID job) throws SQLException {
        if (progress(connection, job) != null) return;
        try (var query = connection.prepareStatement("SELECT valid_rows FROM cfg_import_job WHERE id=?")) {
            query.setObject(1, job);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "IMPORT_UNAVAILABLE");
                long batches = (rows.getInt(1) + 99L) / 100;
                require(dataset.dataVersion() <= 9_007_199_254_740_991L - batches, "DATA_VERSION_LIMIT");
            }
        }
        JdbcNativeDatasetCatalog.execute(connection, """
                INSERT INTO cfg_import_progress(job_id,account_id,actor_id,status,revision,next_row,chunk_size,generation,expected_data_version)
                VALUES(?,?,?,'RUNNING',1,0,100,?,?)
                """, job, dataset.accountId(), actor, dataset.generation(), dataset.dataVersion());
    }

    static JsonNode result(String canonical) {
        return NativeImportPlans.payload(canonical, false);
    }

    static JsonNode progress(Connection connection, UUID job) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM cfg_import_progress WHERE job_id=?")) {
            query.setObject(1, job);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                return JSON.objectNode().put("jobId", job.toString()).put("status", rows.getString("status"))
                        .put("revision", rows.getLong("revision")).put("committedRows", rows.getInt("next_row"))
                        .put("chunkSize", rows.getInt("chunk_size")).put("generation", rows.getObject("generation", UUID.class).toString())
                        .put("dataVersion", rows.getLong("expected_data_version")).put("outcomeCode", rows.getString("outcome_code"));
            }
        }
    }

    static void advance(DataSource source, Clock clock, UUID account, UUID actor, UUID job, long expectedRevision) {
        require(expectedRevision >= 1, "JOB_REVISION_INVALID");
        try {
            NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
                UUID datasetId = JdbcNativeImports.jobDataset(connection, account, actor, job);
                var saved = JdbcNativeImports.job(connection, account, actor, datasetId, job);
                NativeImportIntegrity.verify(job, account, actor, saved);
                var progress = progress(connection, job);
                require(progress != null, "JOB_UNAVAILABLE");
                require(expectedRevision <= progress.path("revision").longValue(), "JOB_CONFLICT");
                if (expectedRevision < progress.path("revision").longValue() || !progress.path("status").asText().equals("RUNNING")) return null;
                require(saved.path("status").asText().equals("PREPARED"), "REVIEW_STATE_CONFLICT");
                require(clock.instant().isBefore(Instant.parse(saved.path("expires_at").asText())), "REVIEW_EXPIRED");
                require(scope.equals(saved.path("scope_digest").asText()), "ACCESS_SCOPE_CHANGED");
                var document = PARSER.parse(saved.path("canonical_document").asText());
                require(document.spec().path("mode").asText().equals("CHUNKED_UPSERT"), "JOB_STATE_CONFLICT");
                var current = NativeRowTransactions.head(connection, account, datasetId, document.spec().at("/target/schemaRevision").longValue());
                require(current.generation().toString().equals(progress.path("generation").asText())
                        && current.dataVersion() == progress.path("dataVersion").longValue(), "TARGET_CONFLICT");
                var plan = NativeImportPlans.decode(saved.path("canonical_plan").asText());
                var records = new ArrayList<JsonNode>();
                plan.forEach(row -> { if (row.has("document")) records.add(row); });
                int from = progress.path("committedRows").intValue();
                require(from >= 0 && from <= records.size(), "IMPORT_INTEGRITY");
                require(progress.path("chunkSize").intValue() == 100, "IMPORT_INTEGRITY");
                var prior = previous(connection, current, job, document.spec(), records, from);
                var references = NativeImportReferences.recheck(connection, current, plan, current.dataVersion(), prior);
                int through = Math.min(from + progress.path("chunkSize").intValue(), records.size());
                if (from == through) {
                    finish(connection, current, job, records.size(), progress.path("revision").longValue() + 1);
                    return null;
                }
                var effective = new ArrayList<JsonNode>();
                for (int i = from; i < through; i++) {
                    var row = records.get(i);
                    var mutation = PARSER.parse(row.path("document").asText()).json();
                    ((ObjectNode) mutation.at("/spec/targetPrecondition")).put("dataVersion", current.dataVersion());
                    var typed = PARSER.parse(mutation.toString());
                    String canonical = NativeMutationValidation.compile(connection, current, scope, typed, UUID.fromString(row.path("recordId").asText()), references);
                    var spec = PARSER.parse(canonical).spec();
                    if (row.has("matchedRowVersion")) ((ObjectNode) spec).put("operation", "UPDATE").set("expectedRowVersion", row.path("matchedRowVersion"));
                    effective.add(spec);
                }
                int chunk = from / progress.path("chunkSize").intValue();
                var receipt = JSON.objectNode().put("jobId", job.toString()).put("chunkIndex", chunk)
                        .put("fromRow", from).put("throughRow", through).put("generation", current.generation().toString())
                        .put("dataVersion", current.dataVersion() + 1);
                var outcomes = receipt.putArray("rows");
                for (int i = from; i < through; i++) {
                    var changed = NativeRowDml.mutate(connection, current, effective.get(i - from), UUID.fromString(records.get(i).path("recordId").asText()), false);
                    outcomes.addObject().put("recordId", changed.recordId().toString()).put("recordVersion", Long.toString(changed.recordVersion()));
                }
                NativeRowDml.advanceHead(connection, current);
                String bytes = VclCanonical.encode(receipt);
                JdbcNativeDatasetCatalog.execute(connection, "INSERT INTO cfg_import_chunk(job_id,chunk_index,from_row,through_row,payload_digest,result_json) VALUES(?,?,?,?,?,?)",
                        job, chunk, from, through, VclCanonical.sha256(bytes), bytes);
                UUID event = UUID.nameUUIDFromBytes(("NATIVE_IMPORT_CHUNK/1:" + job + ":" + chunk).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                JdbcNativeDatasetCatalog.execute(connection, "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_IMPORT_CHUNK_COMMITTED',?)",
                        event, account, datasetId, bytes);
                JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_import_progress SET next_row=?,expected_data_version=?,revision=revision+1,outcome_code=NULL,worker_retry_after=NULL,updated_at=CURRENT_TIMESTAMP WHERE job_id=? AND revision=?",
                        through, current.dataVersion() + 1, job, expectedRevision);
                if (through == records.size()) {
                    var head = new NativeDataset(account, current.generation(), current.dataVersion() + 1, current.recordIdFieldId(), current.schema(), current.physicalId());
                    finish(connection, head, job, records.size(), expectedRevision + 1);
                }
                return null;
            });
        } catch (VclException failure) {
            if (List.of("ROW_COMMIT_UNCERTAIN", "NATIVE_STORAGE_UNAVAILABLE", "NATIVE_CATALOG_DRIFT", "IMPORT_INTEGRITY", "ACCOUNT_ACCESS_REVOKED", "JOB_UNAVAILABLE", "JOB_CONFLICT").contains(failure.code())) throw failure;
            fail(source, account, actor, job, expectedRevision, failure.code());
        }
    }

    private static Map<UUID, Long> previous(Connection connection, NativeDataset current, UUID job, JsonNode spec, List<JsonNode> plan, int through) throws SQLException {
        var versions = new LinkedHashMap<UUID, Long>();
        int next = 0, index = 0;
        long version = spec.at("/targetPrecondition/dataVersion").longValue();
        try (var query = connection.prepareStatement("SELECT * FROM cfg_import_chunk WHERE job_id=? ORDER BY chunk_index")) {
            query.setObject(1, job);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String bytes = rows.getString("result_json");
                    require(VclCanonical.sha256(bytes).equals(rows.getString("payload_digest")), "IMPORT_INTEGRITY");
                    JsonNode receipt = result(bytes);
                    require(rows.getInt("chunk_index") == index++ && rows.getInt("from_row") == next
                            && receipt.path("jobId").asText().equals(job.toString())
                            && receipt.path("fromRow").intValue() == next && receipt.path("chunkIndex").intValue() == index - 1
                            && receipt.path("generation").asText().equals(current.generation().toString())
                            && receipt.path("dataVersion").longValue() == ++version, "IMPORT_INTEGRITY");
                    int end = rows.getInt("through_row");
                    require(end > next && end <= through && receipt.path("throughRow").intValue() == end
                            && receipt.path("rows").size() == end - next, "IMPORT_INTEGRITY");
                    for (int ordinal = next; ordinal < end; ordinal++) {
                        var row = receipt.path("rows").get(ordinal - next);
                        require(row.path("recordId").equals(plan.get(ordinal).path("recordId"))
                                && row.path("recordVersion").asText().equals(Long.toString(NativeStageRows.version(plan.get(ordinal)))), "IMPORT_INTEGRITY");
                        versions.put(UUID.fromString(row.path("recordId").asText()), Long.parseLong(row.path("recordVersion").asText()));
                    }
                    next = end;
                }
            }
        }
        require(next == through && current.dataVersion() == version, "IMPORT_INTEGRITY");
        return versions;
    }

    private static void finish(Connection connection, NativeDataset current, UUID job, int rows, long revision) throws SQLException {
        var receipt = JSON.objectNode().put("jobId", job.toString()).put("datasetId", current.schema().id().toString())
                .put("generation", current.generation().toString()).put("dataVersion", current.dataVersion())
                .put("committedRows", rows).put("atomic", false);
        var chunks = receipt.putArray("chunks");
        try (var query = connection.prepareStatement("SELECT chunk_index,payload_digest,result_json FROM cfg_import_chunk WHERE job_id=? ORDER BY chunk_index")) {
            query.setObject(1, job);
            try (var records = query.executeQuery()) {
                while (records.next()) {
                    String bytes = records.getString("result_json");
                    require(VclCanonical.sha256(bytes).equals(records.getString("payload_digest")), "IMPORT_INTEGRITY");
                    chunks.add(result(bytes));
                }
            }
        }
        String bytes = VclCanonical.encode(receipt);
        JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_import_progress SET status='SUCCEEDED',revision=?,outcome_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE job_id=?", revision, job);
        JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_import_job SET status='SUCCEEDED',result_json=? WHERE id=?", bytes, job);
        JdbcNativeDatasetCatalog.execute(connection, "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_IMPORT_COMPLETED',?)", job, current.accountId(), current.schema().id(), bytes);
    }

    private static void fail(DataSource source, UUID account, UUID actor, UUID job, long revision, String code) {
        NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            UUID dataset = JdbcNativeImports.jobDataset(connection, account, actor, job);
            JdbcNativeImports.job(connection, account, actor, dataset, job);
            var progress = progress(connection, job);
            if (progress == null || progress.path("revision").longValue() != revision || !progress.path("status").asText().equals("RUNNING")) return null;
            String status = progress.path("committedRows").intValue() == 0 ? "FAILED" : "PARTIAL";
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_import_progress SET status=?,outcome_code=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE job_id=?", status, code, job);
            return null;
        });
    }

    private static void require(boolean condition, String code) { if (!condition) throw new VclException(code, ""); }
}
