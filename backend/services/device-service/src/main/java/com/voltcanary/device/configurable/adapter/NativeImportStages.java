package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/** Recoverable shadow provisioning precedes the single atomic active-head/receipt cutover. */
final class NativeImportStages {

    private static final VclParser PARSER = new VclParser();

    private record Preparation(NativeDataset stage, JsonNode rows) {}

    private NativeImportStages() {}

    static void provision(
        DataSource source,
        Clock clock,
        UUID account,
        UUID actor,
        UUID datasetId,
        UUID job,
        String digest,
        String key
    ) {
        var prepared = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = JdbcNativeImports.job(connection, account, actor, datasetId, job);
            NativeImportIntegrity.verify(job, account, actor, saved);
            require(saved.path("review_digest").asText().equals(digest), "REVIEW_CONFLICT");
            require(saved.path("idempotency_key").asText().equals(key), "IDEMPOTENCY_KEY_REUSED");
            if (saved.path("status").asText().equals("SUCCEEDED")) return null;
            var document = PARSER.parse(saved.path("canonical_document").asText());
            if (!document.spec().path("mode").asText().equals("STAGED_REPLACE")) return null;
            require(saved.path("status").asText().equals("PREPARED"), "REVIEW_STATE_CONFLICT");
            require(
                clock.instant().isBefore(Instant.parse(saved.path("expires_at").asText())),
                "REVIEW_EXPIRED"
            );
            require(scope.equals(saved.path("scope_digest").asText()), "ACCESS_SCOPE_CHANGED");
            require(
                VclCanonical.sha256(saved.path("canonical_document").asText()).equals(
                    saved.path("document_digest").asText()
                ) &&
                    VclCanonical.sha256(saved.path("canonical_plan").asText()).equals(
                        saved.path("plan_digest").asText()
                    ),
                "IMPORT_INTEGRITY"
            );
            var old = NativeRowTransactions.head(
                connection,
                account,
                datasetId,
                document.spec().at("/target/schemaRevision").longValue()
            );
            require(
                old
                    .generation()
                    .toString()
                    .equals(document.spec().at("/targetPrecondition/generation").asText()) &&
                    old.dataVersion() ==
                        document.spec().at("/targetPrecondition/dataVersion").longValue(),
                "VALIDATION_STALE"
            );
            var rows = NativeImportPlans.decode(saved.path("canonical_plan").asText());
            var stage = stage(connection, old, job, rows.get(0));
            NativeImportPlans.recheck(connection, old, scope, rows);
            String status = status(connection, stage, job);
            if (status.equals("READY")) {
                NativeStageRows.verify(connection, stage, rows);
                return null;
            }
            require(status.equals("PROVISIONING"), "STAGE_STATE_CONFLICT");
            return new Preparation(stage, rows);
        });
        if (prepared == null) return;
        // H2 DDL commits implicitly. Never execute shadow DDL inside the active data transaction.
        // All names are reserved in the durable journal, and no public read routes to this table.
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            JdbcNativeDatasetCatalog.timeouts(connection);
            var layout = new NativeSqlLayout(prepared.stage());
            JdbcNativeDatasetCatalog.execute(connection, layout.createTable());
            if (prepared.stage().schema().history()) JdbcNativeDatasetCatalog.execute(
                connection,
                layout.createHistory()
            );
            for (var index : layout.indexes())
                JdbcNativeDatasetCatalog.execute(connection, layout.createIndex(index));
            NativeCatalogReadiness.verify(connection, layout);
            connection.commit();
        } catch (SQLException failure) {
            throw NativeRowTransactions.failure(failure);
        }
        NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = JdbcNativeImports.job(connection, account, actor, datasetId, job);
            NativeImportIntegrity.verify(job, account, actor, saved);
            if (saved.path("status").asText().equals("SUCCEEDED")) return null;
            require(saved.path("status").asText().equals("PREPARED"), "REVIEW_STATE_CONFLICT");
            require(
                clock.instant().isBefore(Instant.parse(saved.path("expires_at").asText())),
                "REVIEW_EXPIRED"
            );
            require(scope.equals(saved.path("scope_digest").asText()), "ACCESS_SCOPE_CHANGED");
            var document = PARSER.parse(saved.path("canonical_document").asText());
            var old = NativeRowTransactions.head(
                connection,
                account,
                datasetId,
                document.spec().at("/target/schemaRevision").longValue()
            );
            require(
                old
                    .generation()
                    .toString()
                    .equals(document.spec().at("/targetPrecondition/generation").asText()) &&
                    old.dataVersion() ==
                        document.spec().at("/targetPrecondition/dataVersion").longValue(),
                "VALIDATION_STALE"
            );
            var stage = stage(connection, old, job, prepared.rows().get(0));
            if (status(connection, stage, job).equals("READY")) NativeStageRows.verify(
                connection,
                stage,
                prepared.rows()
            );
            else {
                require(
                    status(connection, stage, job).equals("PROVISIONING"),
                    "STAGE_STATE_CONFLICT"
                );
                NativeImportPlans.recheck(connection, old, scope, prepared.rows());
                NativeStageRows.populate(connection, stage, prepared.rows());
                NativeStageRows.verify(connection, stage, prepared.rows());
                JdbcNativeDatasetCatalog.execute(
                    connection,
                    "UPDATE cfg_native_generation SET status='READY' WHERE dataset_id=? AND generation=? AND import_job_id=?",
                    datasetId,
                    stage.generation(),
                    job
                );
            }
            return null;
        });
    }

    static void cutover(
        Connection connection,
        NativeDataset old,
        UUID job,
        JsonNode rows
    ) throws SQLException {
        NativeHistoryCapture.ensure(connection, old);
        var manifest = rows.get(0);
        var stage = stage(connection, old, job, manifest);
        require(status(connection, stage, job).equals("READY"), "STAGE_STATE_CONFLICT");
        NativeStageRows.checkOriginal(connection, old, rows);
        NativeStageRows.verify(connection, stage, rows);
        NativeStageRows.checkReferences(connection, old, rows, manifest.path("removed"));
        NativeStageRows.tombstones(connection, old, manifest.path("removed"));
        NativeHistoryCapture.retire(connection, old);
        require(old.dataVersion() < 9_007_199_254_740_991L, "DATA_VERSION_LIMIT");
        try (
            var update = connection.prepareStatement(
                """
                UPDATE cfg_dataset SET generation=?,physical_id=?,data_version=data_version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND account_id=? AND generation=? AND physical_id=? AND data_version=? AND status='READY'
                """
            )
        ) {
            update.setObject(1, stage.generation());
            update.setObject(2, stage.physicalId());
            update.setObject(3, old.schema().id());
            update.setObject(4, old.accountId());
            update.setObject(5, old.generation());
            update.setObject(6, old.physicalId());
            update.setLong(7, old.dataVersion());
            require(update.executeUpdate() == 1, "VALIDATION_STALE");
        }
        JdbcNativeDatasetCatalog.execute(
            connection,
            "UPDATE cfg_native_generation SET status='RETIRED',retired_at=CURRENT_TIMESTAMP WHERE dataset_id=? AND generation=? AND status='ACTIVE'",
            old.schema().id(),
            old.generation()
        );
        JdbcNativeDatasetCatalog.execute(
            connection,
            "UPDATE cfg_native_generation SET status='ACTIVE' WHERE dataset_id=? AND generation=? AND status='READY'",
            old.schema().id(),
            stage.generation()
        );
        for (var index : new NativeSqlLayout(stage).indexes()) {
            JdbcNativeDatasetCatalog.execute(
                connection,
                "UPDATE cfg_index_manifest SET physical_name=?,status='READY' WHERE dataset_id=? AND schema_revision=? AND field_id=?",
                index.name(),
                stage.schema().id(),
                stage.schema().revision(),
                index.fieldId()
            );
        }
        var result = JsonNodeFactory.instance
            .objectNode()
            .put("jobId", job.toString())
            .put("datasetId", old.schema().id().toString())
            .put("generation", stage.generation().toString())
            .put("previousGeneration", old.generation().toString())
            .put("dataVersion", old.dataVersion() + 1);
        var receipts = result.putArray("rows");
        for (var row : rows)
            if (row.has("document")) receipts.addObject().set("recordId", row.path("recordId"));
        int index = 0;
        for (var row : rows)
            if (row.has("document")) (
                (com.fasterxml.jackson.databind.node.ObjectNode) receipts.get(index++)
            ).put("recordVersion", Long.toString(NativeStageRows.version(row)));
        result.set("removed", manifest.path("removed"));
        String receipt = VclCanonical.encode(result);
        JdbcNativeDatasetCatalog.execute(
            connection,
            "UPDATE cfg_import_job SET status='SUCCEEDED',result_json=? WHERE id=?",
            receipt,
            job
        );
        JdbcNativeDatasetCatalog.execute(
            connection,
            "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_GENERATION_REPLACED',?)",
            job,
            old.accountId(),
            old.schema().id(),
            receipt
        );
    }

    private static NativeDataset stage(
        Connection connection,
        NativeDataset old,
        UUID job,
        JsonNode manifest
    ) throws SQLException {
        require(
            manifest != null &&
                manifest.path("profile").asText().equals("native-generation-replace-1"),
            "STAGE_INTEGRITY"
        );
        var stage = new NativeDataset(
            old.accountId(),
            UUID.fromString(manifest.path("generation").asText()),
            old.dataVersion() + 1,
            old.recordIdFieldId(),
            old.schema(),
            UUID.fromString(manifest.path("physicalId").asText())
        );
        status(connection, stage, job);
        return stage;
    }

    private static String status(
        Connection connection,
        NativeDataset stage,
        UUID job
    ) throws SQLException {
        try (
            var query = connection.prepareStatement(
                """
                SELECT status FROM cfg_native_generation WHERE dataset_id=? AND generation=? AND physical_id=?
                AND account_id=? AND schema_revision=? AND import_job_id=? FOR UPDATE
                """
            )
        ) {
            query.setObject(1, stage.schema().id());
            query.setObject(2, stage.generation());
            query.setObject(3, stage.physicalId());
            query.setObject(4, stage.accountId());
            query.setLong(5, stage.schema().revision());
            query.setObject(6, job);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "STAGE_INTEGRITY");
                return rows.getString(1);
            }
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
