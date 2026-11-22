package com.voltcanary.device.configurable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog;
import com.voltcanary.device.configurable.adapter.NativeDatasetMetadata;
import com.voltcanary.device.configurable.adapter.NativeSqlLayout;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclUnits;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class NativeCatalogContract {

    abstract DataSource source();

    private UUID account(UUID actor) {
        var id = UUID.randomUUID();
        new JdbcTemplate(source()).update(
            "INSERT INTO accounts(id,user_id,status,updated_at) VALUES(?,?,'ACTIVE',CURRENT_TIMESTAMP)",
            id,
            actor
        );
        return id;
    }

    @Test
    void realTypedTablesAndOnlySelectedIndexesBecomeReady() throws Exception {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var id = UUID.randomUUID();
        var schema = NativeFixtures.schema(account, id, false);
        var catalog = new JdbcNativeDatasetCatalog(source());
        var generation = UUID.randomUUID();
        var systemKey = UUID.randomUUID();
        catalog.register(account, actor, generation, systemKey, schema);
        assertTrue(catalog.ready(account, id, 1).isEmpty());
        var dataset = catalog.provision(account, id);
        assertEquals(schema.digest(), dataset.schema().digest());
        assertEquals(generation, catalog.ready(account, id, 1).orElseThrow().generation());
        assertEquals(
            systemKey.toString(),
            dataset.metadata().at("/systemFields/recordIdFieldId").asText()
        );
        var layout = new NativeSqlLayout(schema);
        try (var connection = source().getConnection()) {
            try (
                var history = connection
                    .getMetaData()
                    .getTables(
                        connection.getCatalog(),
                        connection.getSchema(),
                        layout.historyTable(),
                        null
                    )
            ) {
                assertFalse(history.next());
            }
            int columns = 0;
            try (
                var rows = connection
                    .getMetaData()
                    .getColumns(
                        connection.getCatalog(),
                        connection.getSchema(),
                        layout.table(),
                        "%"
                    )
            ) {
                while (rows.next()) columns++;
            }
            assertEquals(layout.columns().size() + 5, columns);
        }
        assertEquals(
            2,
            new JdbcTemplate(source()).queryForObject(
                "SELECT COUNT(*) FROM cfg_index_manifest WHERE dataset_id=? AND status='READY'",
                Integer.class,
                id
            )
        );
        assertEquals(1, catalog.list(account, 100).size());
        assertEquals(
            1,
            new JdbcTemplate(source()).queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE id=?",
                Integer.class,
                account
            )
        );
    }

    @Test
    void optionalHistoryIsADenseTableAndProvisioningResumesOriginalIdentity() throws Exception {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var schema = NativeFixtures.schema(account, UUID.randomUUID(), true);
        var catalog = new JdbcNativeDatasetCatalog(source());
        var generation = UUID.randomUUID();
        var key = UUID.randomUUID();
        catalog.register(account, actor, generation, key, schema);
        var restarted = new JdbcNativeDatasetCatalog(source());
        var result = restarted.provision(account, schema.id());
        restarted.register(account, actor, generation, key, schema);
        assertEquals(generation, restarted.provision(account, schema.id()).generation());
        try (
            var connection = source().getConnection();
            var rows = connection
                .getMetaData()
                .getTables(
                    connection.getCatalog(),
                    connection.getSchema(),
                    new NativeSqlLayout(schema).historyTable(),
                    null
                )
        ) {
            assertTrue(rows.next());
        }
        assertEquals(1L, result.dataVersion());
    }

    @Test
    void foreignActorAccountAndSchemaRevisionCannotExposeMetadata() {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var schema = NativeFixtures.schema(account, UUID.randomUUID(), false);
        var catalog = new JdbcNativeDatasetCatalog(source());
        assertEquals(
            "ACCOUNT_ACCESS_REVOKED",
            assertThrows(VclException.class, () ->
                catalog.register(
                    account,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    schema
                )
            ).code()
        );
        catalog.register(account, actor, UUID.randomUUID(), UUID.randomUUID(), schema);
        catalog.provision(account, schema.id());
        var other = account(UUID.randomUUID());
        assertTrue(catalog.ready(other, schema.id(), 1).isEmpty());
        assertTrue(catalog.ready(account, schema.id(), 2).isEmpty());
        var metadata = new NativeDatasetMetadata(source(), catalog, account, actor);
        assertTrue(metadata.dataset(schema.id(), 1).isPresent());
        new JdbcTemplate(source()).update(
            "UPDATE accounts SET status='DISABLED' WHERE id=?",
            account
        );
        assertEquals(
            "ACCOUNT_ACCESS_REVOKED",
            assertThrows(VclException.class, metadata::accessScopeDigest).code()
        );
        assertTrue(catalog.ready(account, schema.id(), 1).isEmpty());
    }

    @Test
    void missingReadyTableOrIndexIsNeverSilentlyRecreated() {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var schema = NativeFixtures.schema(account, UUID.randomUUID(), false);
        var catalog = new JdbcNativeDatasetCatalog(source());
        catalog.register(account, actor, UUID.randomUUID(), UUID.randomUUID(), schema);
        catalog.provision(account, schema.id());
        var layout = new NativeSqlLayout(schema);
        new JdbcTemplate(source()).execute(
            "DROP INDEX " + NativeSqlLayout.identifier(layout.indexes().getFirst().name())
        );
        assertEquals(
            "NATIVE_CATALOG_DRIFT",
            assertThrows(VclException.class, () -> catalog.ready(account, schema.id(), 1)).code()
        );
        assertEquals(
            "NATIVE_CATALOG_DRIFT",
            assertThrows(VclException.class, () -> catalog.provision(account, schema.id())).code()
        );
        new JdbcTemplate(source()).execute(
            "DROP TABLE " + NativeSqlLayout.identifier(layout.table())
        );
        assertEquals(
            "NATIVE_CATALOG_DRIFT",
            assertThrows(VclException.class, () -> catalog.provision(account, schema.id())).code()
        );
    }

    @Test
    void cachedCompilationDoesNotHideStoredSchemaTampering() {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var schema = NativeFixtures.schema(account, UUID.randomUUID(), false);
        var catalog = new JdbcNativeDatasetCatalog(source());
        catalog.register(account, actor, UUID.randomUUID(), UUID.randomUUID(), schema);
        catalog.provision(account, schema.id());
        assertEquals(
            schema.digest(),
            catalog.ready(account, schema.id(), 1).orElseThrow().schema().digest()
        );

        var changed = NativeFixtures.document(schema.id(), false);
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("spec")).put(
            "label",
            "Different stored label"
        );
        new JdbcTemplate(source()).update(
            "UPDATE cfg_schema_revision SET canonical_document=? WHERE dataset_id=? AND revision=1",
            changed.toString(),
            schema.id()
        );
        assertEquals(
            "SCHEMA_INTEGRITY",
            assertThrows(VclException.class, () -> catalog.ready(account, schema.id(), 1)).code()
        );
    }

    @Test
    void realOwnerMetadataCompilesQueryWithoutSyntheticDatasetFixture() {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var schema = NativeFixtures.schema(account, UUID.randomUUID(), false);
        var catalog = new JdbcNativeDatasetCatalog(source());
        catalog.register(account, actor, UUID.randomUUID(), UUID.randomUUID(), schema);
        catalog.provision(account, schema.id());
        var parser = new VclParser();
        String query = """
        {"language":"VCL/1","kind":"QUERY","spec":{
          "target":{"datasetId":"%s","schemaRevision":1},"select":["%s"],
          "where":{"op":"CONST","literal":{"type":"BOOLEAN","value":true}},
          "order":[{"fieldId":"%s","direction":"ASC"}],"limit":10,"cursor":null,"read":{"mode":"LIVE"}}}
        """.formatted(schema.id(), NativeFixtures.TEXT, NativeFixtures.TEXT);
        var compiler = new VclCompiler(new VclRegistry(parser), VclUnits.standard());
        var metadata = new NativeDatasetMetadata(source(), catalog, account, actor);
        var result = compiler.compile(parser.parse(query), metadata);
        assertEquals(1, result.dependencies().size());
        assertEquals(schema.id(), result.dependencies().getFirst().id());
        assertEquals(metadata.accessScopeDigest(), result.accessScopeDigest());
    }

    @Test
    void foreignDatasetReferenceAndConflictingSchemaAreRejectedBeforeDdl() {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var catalog = new JdbcNativeDatasetCatalog(source());
        var document = NativeFixtures.document(UUID.randomUUID(), false);
        var reference = (com.fasterxml.jackson.databind.node.ObjectNode) document.at(
            "/spec/fields/8/target"
        );
        reference.remove("resourceKind");
        reference.put("kind", "DATASET").put("datasetId", UUID.randomUUID().toString());
        var foreign = NativeSchema.compile(
            document.toString(),
            JdbcNativeDatasetCatalog.emptyMetadata(account)
        );
        assertEquals(
            "REFERENCE_TARGET_UNAVAILABLE",
            assertThrows(VclException.class, () ->
                catalog.register(account, actor, UUID.randomUUID(), UUID.randomUUID(), foreign)
            ).code()
        );
        var schema = NativeFixtures.schema(account, UUID.randomUUID(), false);
        var generation = UUID.randomUUID();
        var key = UUID.randomUUID();
        catalog.register(account, actor, generation, key, schema);
        assertEquals(
            "DATASET_ID_REUSED",
            assertThrows(VclException.class, () ->
                catalog.register(account, actor, UUID.randomUUID(), key, schema)
            ).code()
        );
    }

    @Test
    void assignmentsRespectActualNativeDecimalScaleAndTextLength() {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var schema = NativeFixtures.schema(account, UUID.randomUUID(), false);
        var catalog = new JdbcNativeDatasetCatalog(source());
        catalog.register(account, actor, UUID.randomUUID(), UUID.randomUUID(), schema);
        var dataset = catalog.provision(account, schema.id());
        var metadata = new NativeDatasetMetadata(source(), catalog, account, actor);
        var parser = new VclParser();
        var compiler = new VclCompiler(new VclRegistry(parser), VclUnits.standard());
        var root = NativeFixtures.MAPPER.createObjectNode()
            .put("language", "VCL/1")
            .put("kind", "MUTATION");
        var spec = root
            .putObject("spec")
            .put("mutationId", UUID.randomUUID().toString())
            .put("operation", "UPDATE")
            .put("expectedRowVersion", "1")
            .put("reviewId", UUID.randomUUID().toString())
            .put("idempotencyKey", "bound-check");
        spec.putObject("target").put("datasetId", schema.id().toString()).put("schemaRevision", 1);
        spec.putObject("targetPrecondition")
            .put("mode", "NATIVE")
            .put("generation", dataset.generation().toString())
            .put("dataVersion", 1);
        var key = spec
            .putArray("key")
            .addObject()
            .put("fieldId", NativeFixtures.TEXT.toString())
            .putObject("literal")
            .put("type", "TEXT")
            .put("value", "key");
        var value = spec
            .putArray("values")
            .addObject()
            .put("fieldId", NativeFixtures.AMOUNT.toString())
            .putObject("literal")
            .put("type", "DECIMAL")
            .put("unit", "V")
            .put("value", "1000000000000000");
        assertEquals(
            "PRECISION_LOSS",
            assertThrows(VclException.class, () ->
                compiler.compile(parser.parse(root.toString()), metadata)
            ).code()
        );
        value.put("value", "1.0001");
        assertEquals(
            "PRECISION_LOSS",
            assertThrows(VclException.class, () ->
                compiler.compile(parser.parse(root.toString()), metadata)
            ).code()
        );
        value.put("value", "1.001");
        key.put("value", "x".repeat(121));
        assertEquals(
            "TEXT_LIMIT",
            assertThrows(VclException.class, () ->
                compiler.compile(parser.parse(root.toString()), metadata)
            ).code()
        );
        key.put("value", "😀".repeat(120));
        compiler.compile(parser.parse(root.toString()), metadata);
    }

    @Test
    void expiredReviewCannotRenewReservationOrStartDispatch() {
        var actor = UUID.randomUUID();
        var account = account(actor);
        var start = java.time.Clock.fixed(
            java.time.Instant.parse("2026-11-03T00:00:00Z"),
            java.time.ZoneOffset.UTC
        );
        var store = new com.voltcanary.device.configurable.adapter.JdbcCollectionReviews(
            source(),
            start
        );
        String key = UUID.randomUUID().toString();
        var reserved = store.reserve(account, actor, java.util.List.of("name"), key);
        var root = NativeFixtures.MAPPER.createObjectNode()
            .put("language", "VCL/1")
            .put("kind", "COLLECTION");
        var spec = root
            .putObject("spec")
            .put("id", reserved.resourceId().toString())
            .put("revision", 1)
            .put("label", "Expiry");
        spec.putArray("fields")
            .addObject()
            .put("id", reserved.fieldIdsByKey().get("name").toString())
            .put("label", "Name")
            .put("type", "TEXT")
            .put("nullable", false)
            .put("maxLength", 32);
        spec.putArray("keyFieldIds");
        spec.putObject("storage")
            .put("mode", "NATIVE")
            .put("history", "NONE")
            .putArray("indexedFieldIds");
        var prepared = store.prepare(
            account,
            actor,
            reserved.id(),
            NativeSchema.compile(root.toString(), JdbcNativeDatasetCatalog.emptyMetadata(account))
        );
        var later = new com.voltcanary.device.configurable.adapter.JdbcCollectionReviews(
            source(),
            java.time.Clock.offset(start, java.time.Duration.ofMinutes(16))
        );
        assertEquals("EXPIRED", later.inspect(account, actor, reserved.id()).status());
        assertEquals(
            "REVIEW_EXPIRED",
            assertThrows(VclException.class, () ->
                later.begin(
                    account,
                    actor,
                    reserved.id(),
                    prepared.revision(),
                    prepared.reviewDigest(),
                    "apply-expired"
                )
            ).code()
        );
        assertEquals(
            reserved.expiresAt(),
            later.reserve(account, actor, java.util.List.of("name"), key).expiresAt()
        );
        assertEquals(
            0,
            new JdbcTemplate(source()).queryForObject(
                "SELECT COUNT(*) FROM cfg_dataset WHERE id=?",
                Integer.class,
                reserved.resourceId()
            )
        );
        assertEquals(
            0,
            new JdbcTemplate(source()).queryForObject(
                "SELECT COUNT(*) FROM cfg_mutation_receipt WHERE review_id=?",
                Integer.class,
                reserved.id()
            )
        );
    }
}
