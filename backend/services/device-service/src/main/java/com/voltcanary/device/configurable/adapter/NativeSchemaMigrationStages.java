package com.voltcanary.device.configurable.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.device.configurable.port.NativeMigrationDependencies;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Journaled DDL and a single fenced schema/generation/receipt/outbox cutover. */
final class NativeSchemaMigrationStages {
    private record Stage(NativeDataset previous, NativeDataset next, JsonNode plan, JsonNode saved) { }
    private final DataSource source;
    private final Clock clock;
    private final NativeMigrationDependencies dependencies;

    NativeSchemaMigrationStages(DataSource source, Clock clock, NativeMigrationDependencies dependencies) {
        this.source = source;
        this.clock = clock;
        this.dependencies = dependencies;
    }

    JsonNode apply(UUID account, UUID actor, UUID dataset, UUID review, long revision, String digest, String key) {
        var initial = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = NativeSchemaMigrationReviews.load(connection, account, actor, dataset, review);
            confirmation(saved, revision, digest, key);
            return saved;
        });
        if (initial.path("status").asText().equals("SUCCEEDED")) return NativeSchemaMigrationReviews.view(initial);
        var owners = dependencies.inspect(account, actor, dataset, initial.path("expected_schema_revision").longValue());
        var prepared = NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = NativeSchemaMigrationReviews.load(connection, account, actor, dataset, review);
            confirmation(saved, revision, digest, key);
            if (saved.path("status").asText().equals("SUCCEEDED")) return null;
            var stage = check(connection, account, dataset, saved, scope, owners);
            String status = journal(connection, stage);
            if (status == null) {
                JdbcNativeDatasetCatalog.execute(connection, """
                        INSERT INTO cfg_native_generation(dataset_id,generation,physical_id,account_id,schema_revision,migration_review_id,status)
                        VALUES(?,?,?,?,?,?,'PROVISIONING')
                        """, dataset, stage.next().generation(), stage.next().physicalId(), account, stage.next().schema().revision(), review);
            } else {
                require(List.of("PROVISIONING", "READY").contains(status), "STAGE_STATE_CONFLICT");
                if (status.equals("READY")) NativeStageRows.verify(connection, stage.next(), stage.plan());
            }
            JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_native_schema_migration SET status='APPLYING' WHERE id=?", review);
            return stage;
        });
        if (prepared == null) return inspect(account, actor, dataset, review);
        // Creating only the reserved shadow is recoverable even on databases whose DDL auto-commits.
        // A READY shadow is verified, never recreated or repaired.
        boolean ddl = NativeRowUnitOfWork.run(source, account, actor,
                (connection, scope) -> "PROVISIONING".equals(journal(connection, prepared)));
        if (ddl) {
            try (var connection = source.getConnection()) {
                connection.setAutoCommit(true);
                var layout = new NativeSqlLayout(prepared.next());
                JdbcNativeDatasetCatalog.execute(connection, layout.createTable());
                if (prepared.next().schema().history()) JdbcNativeDatasetCatalog.execute(connection, layout.createHistory());
                for (var index : layout.indexes()) JdbcNativeDatasetCatalog.execute(connection, layout.createIndex(index));
                NativeCatalogReadiness.verify(connection, layout);
            } catch (SQLException unavailable) { throw NativeRowTransactions.failure(unavailable); }
        }
        NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = NativeSchemaMigrationReviews.load(connection, account, actor, dataset, review);
            confirmation(saved, revision, digest, key);
            if (saved.path("status").asText().equals("SUCCEEDED")) return null;
            var stage = check(connection, account, dataset, saved, scope, owners);
            String status = journal(connection, stage);
            if ("READY".equals(status)) NativeStageRows.verify(connection, stage.next(), stage.plan());
            else {
                require("PROVISIONING".equals(status), "STAGE_STATE_CONFLICT");
                NativeStageRows.populate(connection, stage.next(), stage.plan());
                NativeStageRows.verify(connection, stage.next(), stage.plan());
                JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_native_generation SET status='READY' WHERE dataset_id=? AND generation=? AND migration_review_id=?",
                        dataset, stage.next().generation(), review);
            }
            return null;
        });
        // Owner observations are renewed after provisioning; no database locks span these requests.
        var freshOwners = dependencies.inspect(account, actor, dataset, initial.path("expected_schema_revision").longValue());
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) -> {
            var saved = NativeSchemaMigrationReviews.load(connection, account, actor, dataset, review);
            confirmation(saved, revision, digest, key);
            if (saved.path("status").asText().equals("SUCCEEDED")) return NativeSchemaMigrationReviews.view(saved);
            var stage = check(connection, account, dataset, saved, scope, freshOwners);
            require("READY".equals(journal(connection, stage)), "STAGE_STATE_CONFLICT");
            NativeStageRows.verify(connection, stage.next(), stage.plan());
            NativeSchemaMigrationReviews.usable(saved, scope, clock.instant());
            cutover(connection, stage);
            return NativeSchemaMigrationReviews.view(NativeSchemaMigrationReviews.load(connection, account, actor, dataset, review));
        });
    }

    private JsonNode inspect(UUID account, UUID actor, UUID dataset, UUID review) {
        return NativeRowUnitOfWork.run(source, account, actor, (connection, scope) ->
                NativeSchemaMigrationReviews.view(NativeSchemaMigrationReviews.load(connection, account, actor, dataset, review)));
    }

    private Stage check(Connection connection, UUID account, UUID dataset, JsonNode saved, String scope, JsonNode owners) throws SQLException {
        require(List.of("PREPARED", "APPLYING", "UNKNOWN").contains(saved.path("status").asText()), "REVIEW_STATE_CONFLICT");
        NativeSchemaMigrationReviews.verify(saved);
        NativeSchemaMigrationReviews.usable(saved, scope, clock.instant());
        var previous = NativeSchemaMigrationReviews.head(connection, account, dataset, saved);
        var request = NativeImportPlans.payload(saved.path("canonical_request").asText(), false);
        var nextSchema = NativeSchema.compileRevision(request.path("schema").toString(), JdbcNativeDatasetCatalog.emptyMetadata(account));
        var next = new NativeDataset(account, UUID.fromString(saved.path("new_generation").asText()), previous.dataVersion() + 1,
                previous.recordIdFieldId(), nextSchema, UUID.fromString(saved.path("physical_id").asText()));
        var plan = NativeImportPlans.decode(saved.path("canonical_plan").asText());
        require(!plan.isEmpty() && plan.get(0).path("profile").asText().equals("native-schema-migration-1")
                && plan.get(0).path("targetSchemaDigest").asText().equals(nextSchema.digest())
                && plan.get(0).path("sourceSchemaDigest").asText().equals(previous.schema().digest()), "MIGRATION_INTEGRITY");
        var reviewedImpact = NativeImportPlans.payload(saved.path("impact_json").asText(), false);
        var currentImpact = (com.fasterxml.jackson.databind.node.ObjectNode) NativeMigrationImpact.inspect(connection, previous, nextSchema, owners, clock.instant());
        for (String field : List.of("sourceRows", "validRows", "dataMode", "errors")) currentImpact.set(field, reviewedImpact.path(field));
        require(!currentImpact.path("blocked").asBoolean() && VclCanonical.encode(currentImpact).equals(saved.path("impact_json").asText()), "VALIDATION_STALE");
        var references = NativeImportReferences.recheck(connection, previous, plan);
        var validationTarget = new NativeDataset(account, next.generation(), next.dataVersion(), next.recordIdFieldId(), nextSchema, previous.physicalId());
        for (var row : plan) {
            if (!row.has("document")) continue;
            String canonical = NativeMutationValidation.compile(connection, validationTarget, scope,
                    NativeMutationValidation.PARSER.parse(row.path("document").asText()), UUID.fromString(row.path("recordId").asText()), references);
            require(canonical.equals(row.path("document").asText()), "MIGRATION_INTEGRITY");
        }
        if (request.path("dataMode").asText().equals("RESET")) resetReferences(connection, previous);
        return new Stage(previous, next, plan, saved);
    }

    private static void resetReferences(Connection connection, NativeDataset previous) throws SQLException {
        try (var query = connection.prepareStatement("SELECT record_id FROM " + NativeSqlLayout.identifier(new NativeSqlLayout(previous).table()) + " ORDER BY record_id LIMIT 10001")) {
            var batch = new java.util.HashSet<UUID>();
            int count = 0;
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    require(++count <= 10_000, "MIGRATION_ROW_LIMIT");
                    batch.add(rows.getObject(1, UUID.class));
                    if (batch.size() == 500) {
                        NativeReferenceGuards.replacing(connection, previous, batch);
                        batch.clear();
                    }
                }
            }
            NativeReferenceGuards.replacing(connection, previous, batch);
        }
    }

    private static void confirmation(JsonNode saved, long revision, String digest, String key) {
        require(revision == 2 && revision == saved.path("revision").longValue()
                && digest != null && digest.equals(saved.path("review_digest").asText()), "REVIEW_CONFLICT");
        require(key != null && key.equals(saved.path("reservation_key").asText()), "IDEMPOTENCY_KEY_REUSED");
        NativeSchemaMigrationReviews.verify(saved);
    }

    private static String journal(Connection connection, Stage stage) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT physical_id,schema_revision,status,migration_review_id FROM cfg_native_generation
                WHERE dataset_id=? AND generation=? AND account_id=? FOR UPDATE
                """)) {
            query.setObject(1, stage.next().schema().id());
            query.setObject(2, stage.next().generation());
            query.setObject(3, stage.next().accountId());
            try (var row = query.executeQuery()) {
                if (!row.next()) return null;
                require(row.getObject(1, UUID.class).equals(stage.next().physicalId()) && row.getLong(2) == stage.next().schema().revision()
                        && UUID.fromString(stage.saved().path("id").asText()).equals(row.getObject(4, UUID.class)), "STAGE_INTEGRITY");
                return row.getString(3);
            }
        }
    }

    private static void cutover(Connection connection, Stage stage) throws SQLException {
        var next = stage.next();
        var previous = stage.previous();
        NativeHistoryCapture.retire(connection, previous);
        var schema = next.schema();
        UUID review = UUID.fromString(stage.saved().path("id").asText());
        JdbcNativeDatasetCatalog.execute(connection, "INSERT INTO cfg_schema_revision(dataset_id,revision,document_digest,canonical_document) VALUES(?,?,?,?)",
                schema.id(), schema.revision(), schema.digest(), schema.canonical());
        for (var field : schema.fields()) JdbcNativeDatasetCatalog.execute(connection,
                "INSERT INTO cfg_field(dataset_id,schema_revision,field_id,definition_json) VALUES(?,?,?,?)",
                schema.id(), schema.revision(), UUID.fromString(field.path("id").asText()), VclCanonical.encode(field));
        for (var index : new NativeSqlLayout(next).indexes()) JdbcNativeDatasetCatalog.execute(connection,
                "INSERT INTO cfg_index_manifest(dataset_id,schema_revision,field_id,physical_name,status) VALUES(?,?,?,?,'READY')",
                schema.id(), schema.revision(), index.fieldId(), index.name());
        try (var update = connection.prepareStatement("""
                UPDATE cfg_dataset SET schema_revision=?,generation=?,physical_id=?,data_version=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND account_id=? AND schema_revision=? AND generation=? AND data_version=? AND physical_id=? AND status='READY'
                """)) {
            Object[] parameters = {schema.revision(), next.generation(), next.physicalId(), next.dataVersion(), schema.id(), next.accountId(),
                    previous.schema().revision(), previous.generation(), previous.dataVersion(), previous.physicalId()};
            for (int index = 0; index < parameters.length; index++) update.setObject(index + 1, parameters[index]);
            require(update.executeUpdate() == 1, "VALIDATION_STALE");
        }
        JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_native_generation SET status='RETIRED',retired_at=CURRENT_TIMESTAMP WHERE dataset_id=? AND generation=? AND status='ACTIVE'", schema.id(), previous.generation());
        JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_native_generation SET status='ACTIVE' WHERE dataset_id=? AND generation=? AND status='READY'", schema.id(), next.generation());
        var receipt = NativeSchemaMigrationReviews.receipt(stage.saved());
        String canonical = VclCanonical.encode(receipt);
        JdbcNativeDatasetCatalog.execute(connection, "UPDATE cfg_native_schema_migration SET status='SUCCEEDED',result_json=?,outcome_code=NULL WHERE id=?", canonical, review);
        JdbcNativeDatasetCatalog.execute(connection, "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_SCHEMA_MIGRATED',?)",
                UUID.fromString(stage.saved().path("receipt_id").asText()), next.accountId(), schema.id(), canonical);
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new VclException(code, "");
    }
}
