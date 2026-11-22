package com.voltcanary.device.configurable;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.adapter.JdbcNativeImports;
import com.voltcanary.device.configurable.adapter.NativeSqlLayout;
import com.voltcanary.device.configurable.domain.NativeImport;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

abstract class NativeImportsContract extends NativeRowsContract {

    JdbcNativeImports imports() {
        return new JdbcNativeImports(source(), clock);
    }

    Fixture referencing(Fixture owner, Fixture destination) {
        var doc = NativeFixtures.document(UUID.randomUUID(), true);
        ((ObjectNode) doc.at("/spec/fields/8"))
            .putObject("target")
            .put("kind", "DATASET")
            .put("datasetId", destination.dataset().schema().id().toString());
        (
            (com.fasterxml.jackson.databind.node.ArrayNode) doc.at("/spec/storage/indexedFieldIds")
        ).add(new UUID(0, 9).toString());
        var catalog = new com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog(
            source()
        );
        var schema = com.voltcanary.device.configurable.domain.NativeSchema.compile(
            doc.toString(),
            com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog.emptyMetadata(
                owner.account()
            )
        );
        catalog.register(
            owner.account(),
            owner.actor(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            schema
        );
        return new Fixture(
            owner.actor(),
            owner.account(),
            catalog.provision(owner.account(), schema.id())
        );
    }

    ObjectNode referenceDocument(Fixture target, Fixture referenced, String content, String mode) {
        var doc = document(target, content, "CSV", mode);
        ((ObjectNode) doc.at("/spec/format")).putArray("header").add("name").add("link");
        var column = (ObjectNode) doc.at("/spec/columns/1");
        column
            .put("sourceColumn", "link")
            .put("targetFieldId", new UUID(0, 9).toString())
            .putNull("sourceUnit");
        var conversion = column
            .putArray("conversions")
            .addObject()
            .put("method", "resolve_ref")
            .put("keyFieldId", NativeFixtures.TEXT.toString())
            .put("cardinality", "EXACT_ONE");
        conversion
            .putObject("target")
            .put("datasetId", referenced.dataset().schema().id().toString())
            .put("schemaRevision", 1);
        return doc;
    }

    ObjectNode document(Fixture f, String content, String format, String mode) {
        var artifact = imports().upload(
            f.account(),
            f.actor(),
            UUID.randomUUID().toString(),
            content.getBytes(StandardCharsets.UTF_8)
        );
        var root = NativeFixtures.MAPPER.createObjectNode()
            .put("language", "VCL/1")
            .put("kind", "IMPORT");
        var spec = root
            .putObject("spec")
            .put("contractVersion", "IMPORT/1")
            .put("artifactId", artifact.id().toString())
            .put("artifactSha256", artifact.sha256())
            .put("mappingRevision", 1)
            .put("mode", mode)
            .put("nullPolicy", "SCHEMA_EXPLICIT")
            .put("conflictPolicy", "REJECT_STALE");
        spec.putObject("target")
            .put("datasetId", f.dataset().schema().id().toString())
            .put("schemaRevision", 1);
        spec.putArray("keyFields").add(NativeFixtures.TEXT.toString());
        var kind = spec.putObject("format").put("kind", format).put("encoding", "UTF-8");
        if (format.equals("CSV")) {
            kind.put("delimiter", ",");
            kind.putArray("header").add("name").add("amount");
        }
        var columns = spec.putArray("columns");
        columns
            .addObject()
            .put("sourceColumn", "name")
            .put("targetFieldId", NativeFixtures.TEXT.toString())
            .put("sourceType", "TEXT")
            .putNull("sourceUnit")
            .put("emptyValue", "PRESERVE_EMPTY")
            .putArray("conversions")
            .addObject()
            .put("method", "identity");
        var amount = columns
            .addObject()
            .put("sourceColumn", "amount")
            .put("targetFieldId", NativeFixtures.AMOUNT.toString())
            .put("sourceType", "TEXT")
            .put("sourceUnit", "V")
            .put("emptyValue", "AS_NULL");
        amount
            .putArray("conversions")
            .addObject()
            .put("method", "parse_decimal")
            .put("decimalSeparator", ".")
            .put("scale", 3)
            .put("rounding", "REJECT");
        spec.putObject("limits")
            .put("maxBytes", 1_048_576)
            .put("maxRows", 500)
            .put("maxRowBytes", 8192);
        spec.putObject("provenance")
            .put("sourceId", UUID.randomUUID().toString())
            .put("description", "test source");
        var dataset = head(f);
        spec.putObject("targetPrecondition")
            .put("mode", "NATIVE")
            .put("generation", dataset.generation().toString())
            .put("dataVersion", dataset.dataVersion());
        return root;
    }

    NativeImport validate(Fixture f, ObjectNode document, String key) {
        return imports().validate(
            f.account(),
            f.actor(),
            f.dataset().schema().id(),
            key,
            parser.parse(document.toString())
        );
    }

    NativeImport applyImport(Fixture f, NativeImport review, String key) {
        return imports().apply(
            f.account(),
            f.actor(),
            f.dataset().schema().id(),
            review.id(),
            review.reviewDigest(),
            key
        );
    }

    @Test
    void validationStopsAtRowBudgetWithoutPreparingOrChangingRows() {
        var f = fixture(true);
        var initialHead = head(f);
        var document = document(
            f,
            "name,amount\nexamined,1\nnot-examined,2\n",
            "CSV",
            "ATOMIC_INSERT"
        );
        ((ObjectNode) document.at("/spec/limits")).put("maxRows", 1);
        String key = UUID.randomUUID().toString();
        var result = validate(f, document, key);
        assertEquals("REJECTED", result.status());
        assertFalse(result.validationComplete());
        assertEquals(1, result.processedRows());
        assertEquals(1, result.validRows());
        assertEquals(0, result.invalidRows());
        assertNull(result.totalRows());
        assertNull(result.unprocessedRows());
        assertEquals("ROW_LIMIT_REACHED", result.outcomeCode());
        assertEquals(
            result,
            imports().inspect(f.account(), f.actor(), f.dataset().schema().id(), result.id())
        );
        assertEquals(
            "REVIEW_STATE_CONFLICT",
            assertThrows(VclException.class, () -> applyImport(f, result, key)).code()
        );
        assertEquals(initialHead.dataVersion(), head(f).dataVersion());
    }

    @Test
    void chunkedReplayAndCancellationLeaveOnlyCommittedPrefix() {
        var f = fixture(true);
        String key = UUID.randomUUID().toString();
        StringBuilder csv = new StringBuilder("name,amount\n");
        for (int row = 0; row < 101; row++) csv.append("key-").append(row).append(",1.5\n");
        var prepared = validate(f, document(f, csv.toString(), "CSV", "CHUNKED_UPSERT"), key);
        assertEquals("PREPARED", prepared.status(), prepared.errors().toString());
        var running = applyImport(f, prepared, key);
        assertEquals("RUNNING", running.status());
        long revision = running.progress().path("revision").longValue();
        var firstChunk = imports().advance(f.account(), f.actor(), prepared.id(), revision);
        assertEquals(100, firstChunk.progress().path("committedRows").intValue());
        assertEquals(
            firstChunk,
            imports().advance(f.account(), f.actor(), prepared.id(), revision)
        );
        assertEquals(2, head(f).dataVersion());
        var cancelled = imports().cancel(f.account(), f.actor(), prepared.id());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(100, cancelled.progress().path("committedRows").intValue());
        assertEquals(cancelled, applyImport(f, prepared, key));
        assertEquals(
            100,
            queries(clock)
                .query(f.account(), f.actor(), parser.parse(query(f, 100, null).toString()))
                .rows()
                .size()
        );
    }

    @Test
    void lostChunkCommitAcknowledgementRecoversSameJobAndExactReceipts() {
        var f = fixture(true);
        String key = UUID.randomUUID().toString();
        var csv = new StringBuilder("name,amount\n");
        for (int row = 0; row < 101; row++) csv.append("key-").append(row).append(",1.5\n");
        var review = validate(f, document(f, csv.toString(), "CSV", "CHUNKED_UPSERT"), key);
        var running = applyImport(f, review, key);
        long firstRevision = running.progress().path("revision").longValue();
        var first = imports().advance(f.account(), f.actor(), review.id(), firstRevision);
        assertEquals(100, first.progress().path("committedRows").intValue());
        long secondRevision = first.progress().path("revision").longValue();

        var lostAcknowledgement = new org.springframework.jdbc.datasource.DelegatingDataSource(
            source()
        ) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                var actual = super.getConnection();
                var connection = org.mockito.Mockito.mock(
                    java.sql.Connection.class,
                    org.mockito.AdditionalAnswers.delegatesTo(actual)
                );
                org.mockito.Mockito.doAnswer(call -> {
                    actual.commit();
                    throw new java.sql.SQLRecoverableException(
                        "test lost commit acknowledgement",
                        "08006"
                    );
                })
                    .when(connection)
                    .commit();
                return connection;
            }
        };
        var interrupted = new JdbcNativeImports(lostAcknowledgement, clock);
        assertEquals(
            "ROW_COMMIT_UNCERTAIN",
            assertThrows(VclException.class, () ->
                interrupted.advance(f.account(), f.actor(), review.id(), secondRevision)
            ).code()
        );

        var recovered = imports().advance(f.account(), f.actor(), review.id(), secondRevision);
        assertEquals("SUCCEEDED", recovered.status());
        assertEquals(101, recovered.progress().path("committedRows").intValue());
        assertEquals(2, recovered.result().path("chunks").size());
        assertEquals(100, recovered.result().at("/chunks/1/fromRow").intValue());
        assertEquals(101, recovered.result().at("/chunks/1/throughRow").intValue());
        assertEquals(recovered, applyImport(f, review, key));
        assertEquals(3, head(f).dataVersion());
        assertEquals(
            2,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_outbox WHERE aggregate_id=? AND event_type='NATIVE_IMPORT_CHUNK_COMMITTED'",
                Integer.class,
                f.dataset().schema().id()
            )
        );
    }

    @Test
    void atomicInsertSealsStableIdsAndCommitsOneVersionAndOutboxWithExactReplay() {
        var f = fixture(true);
        String key = UUID.randomUUID().toString();
        var doc = document(f, "name,amount\r\none,12.500\r\ntwo,\r\n", "CSV", "ATOMIC_INSERT");
        var prepared = validate(f, doc, key);
        assertEquals("PREPARED", prepared.status(), prepared.errors().toString());
        assertTrue(prepared.validationComplete());
        assertEquals(2, prepared.validRows());
        assertEquals(prepared, validate(f, doc, key));
        assertEquals(1, head(f).dataVersion());
        var applied = applyImport(f, prepared, key);
        assertEquals("SUCCEEDED", applied.status());
        assertEquals(2, head(f).dataVersion());
        assertEquals(applied, applyImport(f, prepared, key));
        assertEquals(
            applied,
            imports().inspect(f.account(), f.actor(), f.dataset().schema().id(), prepared.id())
        );
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_outbox WHERE id=?",
                Integer.class,
                prepared.id()
            )
        );
        assertEquals(
            2,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " + new NativeSqlLayout(f.dataset().schema()).historyTable(),
                Integer.class
            )
        );
        var queried = queries(clock).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 10, null).toString())
        );
        assertEquals(2, queried.rows().size());
        for (var receipt : applied.result().path("rows"))
            assertTrue(
                queried
                    .rows()
                    .stream()
                    .anyMatch(row ->
                        row.recordId().toString().equals(receipt.path("recordId").asText())
                    )
            );
    }

    @Test
    void upsertPreservesIdIncrementsVersionAndClearsNullableField() {
        var f = fixture(true);
        String firstKey = UUID.randomUUID().toString();
        var initial = applyImport(
            f,
            validate(
                f,
                document(f, "{\"name\":\"same\",\"amount\":\"1.5\"}\n", "JSONL", "ATOMIC_INSERT"),
                firstKey
            ),
            firstKey
        );
        var id = initial.result().at("/rows/0/recordId");
        String key = UUID.randomUUID().toString();
        var applied = applyImport(
            f,
            validate(
                f,
                document(f, "{\"name\":\"same\",\"amount\":null}\n", "JSONL", "ATOMIC_UPSERT"),
                key
            ),
            key
        );
        assertEquals(id, applied.result().at("/rows/0/recordId"));
        assertEquals("2", applied.result().at("/rows/0/recordVersion").asText());
        var queried = queries(clock).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 10, null).toString())
        );
        assertEquals(
            "NULL",
            queried.rows().getFirst().values().get(NativeFixtures.AMOUNT).path("type").asText()
        );
    }

    @Test
    void duplicateAndInvalidRowsRejectWholeManifestWithoutLeakingValues() {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var prepared = validate(
            f,
            document(
                f,
                "name,amount\nsecret,1\nsecret,2\nother,private-invalid-value\n",
                "CSV",
                "ATOMIC_INSERT"
            ),
            key
        );
        assertEquals("REJECTED", prepared.status());
        assertEquals(3, prepared.totalRows());
        assertEquals(2, prepared.invalidRows());
        assertFalse(prepared.errors().toString().contains("secret"));
        assertFalse(prepared.errors().toString().contains("private-invalid-value"));
        assertEquals(
            "REVIEW_STATE_CONFLICT",
            assertThrows(VclException.class, () -> applyImport(f, prepared, key)).code()
        );
        assertEquals(1, head(f).dataVersion());
    }

    @Test
    void staleWriterInvalidatesManifestRatherThanOverwritingChanges() {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var prepared = validate(
            f,
            document(f, "name,amount\nnew,1\n", "CSV", "ATOMIC_INSERT"),
            key
        );
        insert(f, "concurrent");
        assertEquals(
            "VALIDATION_STALE",
            assertThrows(VclException.class, () -> applyImport(f, prepared, key)).code()
        );
        assertEquals(2, head(f).dataVersion());
    }

    @Test
    void outboxFailureRollsBackEveryRowHistoryAndHeadAndCanRetrySameReview() {
        var f = fixture(true);
        String key = UUID.randomUUID().toString();
        var prepared = validate(
            f,
            document(f, "name,amount\none,1\ntwo,2\n", "CSV", "ATOMIC_INSERT"),
            key
        );
        sql().update(
            "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'CONFLICT','{}')",
            prepared.id(),
            f.account(),
            f.dataset().schema().id()
        );
        assertThrows(VclException.class, () -> applyImport(f, prepared, key));
        assertEquals(1, head(f).dataVersion());
        assertEquals(
            0,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " + new NativeSqlLayout(f.dataset().schema()).table(),
                Integer.class
            )
        );
        assertEquals(
            0,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " + new NativeSqlLayout(f.dataset().schema()).historyTable(),
                Integer.class
            )
        );
        assertEquals(
            "PREPARED",
            imports()
                .inspect(f.account(), f.actor(), f.dataset().schema().id(), prepared.id())
                .status()
        );
        sql().update("DELETE FROM cfg_outbox WHERE id=?", prepared.id());
        assertEquals("SUCCEEDED", applyImport(f, prepared, key).status());
    }

    @Test
    void reviewDigestKeyScopeExpiryAndPlanIntegrityAreFenced() {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var doc = document(f, "name,amount\none,1\n", "CSV", "ATOMIC_INSERT");
        var prepared = validate(f, doc, key);
        assertEquals(
            "REVIEW_CONFLICT",
            assertThrows(VclException.class, () ->
                imports().apply(
                    f.account(),
                    f.actor(),
                    prepared.datasetId(),
                    prepared.id(),
                    "0".repeat(64),
                    key
                )
            ).code()
        );
        assertEquals(
            "IDEMPOTENCY_KEY_REUSED",
            assertThrows(VclException.class, () -> applyImport(f, prepared, "other-key")).code()
        );
        var expired = new JdbcNativeImports(source(), Clock.offset(clock, Duration.ofMinutes(16)));
        assertEquals(
            "REVIEW_EXPIRED",
            assertThrows(VclException.class, () ->
                expired.apply(
                    f.account(),
                    f.actor(),
                    prepared.datasetId(),
                    prepared.id(),
                    prepared.reviewDigest(),
                    key
                )
            ).code()
        );
        sql().update("UPDATE cfg_import_job SET canonical_plan='[]' WHERE id=?", prepared.id());
        assertEquals(
            "IMPORT_INTEGRITY",
            assertThrows(VclException.class, () -> applyImport(f, prepared, key)).code()
        );
    }

    @Test
    void foreignActorCannotInspectOrApplyAndAccountRevocationBlocksReplay() {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var prepared = validate(
            f,
            document(f, "name,amount\none,1\n", "CSV", "ATOMIC_INSERT"),
            key
        );
        var other = fixture(false);
        assertEquals(
            "IMPORT_UNAVAILABLE",
            assertThrows(VclException.class, () ->
                imports().inspect(
                    other.account(),
                    other.actor(),
                    prepared.datasetId(),
                    prepared.id()
                )
            ).code()
        );
        assertEquals("SUCCEEDED", applyImport(f, prepared, key).status());
        sql().update("UPDATE accounts SET status='DELETED' WHERE id=?", f.account());
        assertEquals(
            "ACCOUNT_ACCESS_REVOKED",
            assertThrows(VclException.class, () -> applyImport(f, prepared, key)).code()
        );
    }

    @Test
    void completeValidationCountsErrorsBeyondDisplayedLimit() {
        var f = fixture(false);
        String content = "name,amount\n" + "private,invalid\n".repeat(101);
        var prepared = validate(
            f,
            document(f, content, "CSV", "ATOMIC_INSERT"),
            UUID.randomUUID().toString()
        );
        assertEquals(101, prepared.invalidRows());
        assertEquals(100, prepared.errors().size());
        assertTrue(prepared.validationComplete());
        assertTrue(prepared.displayedErrorsTruncated());
    }

    @Test
    void artifactReplayRejectsChangedBytesAndCorruptionOrExpiryCannotValidate() {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var artifact = imports().upload(f.account(), f.actor(), key, new byte[] { 1 });
        assertEquals(artifact, imports().upload(f.account(), f.actor(), key, new byte[] { 1 }));
        assertEquals(
            "IDEMPOTENCY_KEY_REUSED",
            assertThrows(VclException.class, () ->
                imports().upload(f.account(), f.actor(), key, new byte[] { 2 })
            ).code()
        );
        var doc = document(f, "name,amount\none,1\n", "CSV", "ATOMIC_INSERT");
        UUID id = UUID.fromString(doc.at("/spec/artifactId").asText());
        sql().update("UPDATE cfg_import_artifact SET content_base64='corrupt' WHERE id=?", id);
        assertEquals(
            "ARTIFACT_INTEGRITY",
            assertThrows(VclException.class, () ->
                validate(f, doc, UUID.randomUUID().toString())
            ).code()
        );
    }

    @Test
    void unknownPropertiesMissingColumnsDuplicateJsonAndIncompleteCsvDoNotPrepare() {
        var f = fixture(false);
        for (String content : new String[] {
            "{\"name\":\"one\",\"amount\":\"1\",\"extra\":true}\n",
            "{\"name\":\"one\"}\n",
        }) {
            var prepared = validate(
                f,
                document(f, content, "JSONL", "ATOMIC_INSERT"),
                UUID.randomUUID().toString()
            );
            assertEquals("REJECTED", prepared.status());
        }
        for (String content : new String[] {
            "{\"name\":\"one\",\"name\":\"two\",\"amount\":\"1\"}\n",
            "{\"name\":\"one\",\"amount\":\"1\"} trailing\n",
        }) {
            var doc = document(f, content, "JSONL", "ATOMIC_INSERT");
            assertThrows(VclException.class, () -> validate(f, doc, UUID.randomUUID().toString()));
        }
        var doc = document(f, "name,amount\n\"unterminated,1", "CSV", "ATOMIC_INSERT");
        assertEquals(
            "FORMAT_INVALID",
            assertThrows(VclException.class, () ->
                validate(f, doc, UUID.randomUUID().toString())
            ).code()
        );
        assertEquals(1, head(f).dataVersion());
    }

    @Test
    void concurrentApplyOfOneManifestCommitsOneBatchAndReceipt() throws Exception {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var prepared = validate(
            f,
            document(f, "name,amount\none,1\ntwo,2\n", "CSV", "ATOMIC_INSERT"),
            key
        );
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> applyImport(f, prepared, key));
            var second = pool.submit(() -> applyImport(f, prepared, key));
            assertEquals(first.get(), second.get());
        }
        assertEquals(2, head(f).dataVersion());
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_outbox WHERE id=?",
                Integer.class,
                prepared.id()
            )
        );
    }

    @Test
    void differentPreparedBatchesOnOneHeadCannotBothCommit() throws Exception {
        var f = fixture(false);
        String firstKey = UUID.randomUUID().toString(),
            secondKey = UUID.randomUUID().toString();
        var first = validate(
            f,
            document(f, "name,amount\none,1\n", "CSV", "ATOMIC_INSERT"),
            firstKey
        );
        var second = validate(
            f,
            document(f, "name,amount\ntwo,2\n", "CSV", "ATOMIC_INSERT"),
            secondKey
        );
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                try {
                    return applyImport(f, first, firstKey).status();
                } catch (VclException failure) {
                    return failure.code();
                }
            });
            var two = pool.submit(() -> {
                try {
                    return applyImport(f, second, secondKey).status();
                } catch (VclException failure) {
                    return failure.code();
                }
            });
            assertEquals(
                java.util.Set.of("SUCCEEDED", "VALIDATION_STALE"),
                java.util.Set.of(one.get(), two.get())
            );
        }
        assertEquals(2, head(f).dataVersion());
    }

    @Test
    void maximumAtomicBatchCommitsAllFiveHundredRowsWithOneHeadVersion() {
        var f = fixture(false);
        var content = new StringBuilder("name,amount\n");
        for (int i = 0; i < 500; i++) content.append("key-").append(i).append(",1\n");
        String key = UUID.randomUUID().toString();
        var prepared = validate(f, document(f, content.toString(), "CSV", "ATOMIC_INSERT"), key);
        assertEquals(500, prepared.validRows(), prepared.errors().toString());
        assertEquals(500, prepared.typedDiff().path("inserts").intValue());
        assertEquals(500, applyImport(f, prepared, key).result().path("rows").size());
        assertEquals(2, head(f).dataVersion());
    }

    @Test
    void stagedReplacePreservesStableIdsRetainsOldGenerationAndRoutesNewWritesToNewTable() {
        var f = fixture(true);
        var retained = insert(f, "retained");
        insert(f, "removed");
        var before = head(f);
        String key = UUID.randomUUID().toString();
        var review = validate(
            f,
            document(f, "name,amount\nretained,12.5\ncreated,7\n", "CSV", "STAGED_REPLACE"),
            key
        );
        assertEquals("PREPARED", review.status(), review.errors().toString());
        assertEquals(1, review.typedDiff().path("deletes").intValue());
        assertEquals(1, review.typedDiff().path("inserts").intValue());
        assertEquals(1, review.typedDiff().path("updates").intValue());
        var applied = applyImport(f, review, key);
        assertEquals("SUCCEEDED", applied.status());
        var after = head(f);
        assertNotEquals(before.generation(), after.generation());
        assertNotEquals(before.physicalId(), after.physicalId());
        assertEquals(before.dataVersion() + 1, after.dataVersion());
        assertEquals(applied, applyImport(f, review, key));
        var data = queries(clock).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 10, null).toString())
        );
        assertEquals(2, data.rows().size());
        var updated = data
            .rows()
            .stream()
            .filter(row ->
                row.values().get(NativeFixtures.TEXT).path("value").asText().equals("retained")
            )
            .findFirst()
            .orElseThrow();
        assertEquals(retained.createdRecordId(), updated.recordId());
        assertEquals("2", updated.recordVersion());
        assertEquals(
            2,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " + new NativeSqlLayout(before).table(),
                Integer.class
            )
        );
        assertEquals(
            2,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " + new NativeSqlLayout(after).table(),
                Integer.class
            )
        );
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " +
                    new NativeSqlLayout(before).historyTable() +
                    " WHERE deleted=TRUE",
                Integer.class
            )
        );
        insert(f, "after-replace");
        assertEquals(
            3,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " + new NativeSqlLayout(after).table(),
                Integer.class
            )
        );
        assertEquals(
            2,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " + new NativeSqlLayout(before).table(),
                Integer.class
            )
        );
    }

    @Test
    void stagedReplaceRejectsOldWriterAfterCutoverAndStaleStageAfterWriter() {
        var f = fixture(false);
        var oldWriter = reserve(f);
        var writerReview = prepare(
            f,
            oldWriter,
            mutation(f, oldWriter, "INSERT", "obsolete", null)
        );
        String key = UUID.randomUUID().toString();
        var stage = validate(
            f,
            document(f, "name,amount\nreplacement,1\n", "CSV", "STAGED_REPLACE"),
            key
        );
        applyImport(f, stage, key);
        assertEquals(
            "TARGET_CONFLICT",
            assertThrows(VclException.class, () -> apply(f, writerReview)).code()
        );
        String nextKey = UUID.randomUUID().toString();
        var next = validate(
            f,
            document(f, "name,amount\nother,2\n", "CSV", "STAGED_REPLACE"),
            nextKey
        );
        var activeGeneration = head(f).generation();
        insert(f, "newer-write");
        assertEquals(
            "VALIDATION_STALE",
            assertThrows(VclException.class, () -> applyImport(f, next, nextKey)).code()
        );
        assertEquals(activeGeneration, head(f).generation());
        assertEquals(
            2,
            queries(clock)
                .query(f.account(), f.actor(), parser.parse(query(f, 10, null).toString()))
                .rows()
                .size()
        );
    }

    @Test
    void stagedCutoverOutboxFailureRollsBackPointerAndTombstoneThenReusesSameReadyStage() {
        var f = fixture(true);
        insert(f, "removed");
        var old = head(f);
        String key = UUID.randomUUID().toString();
        var review = validate(f, document(f, "name,amount\nnew,1\n", "CSV", "STAGED_REPLACE"), key);
        sql().update(
            "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'CONFLICT','{}')",
            review.id(),
            f.account(),
            review.datasetId()
        );
        assertThrows(VclException.class, () -> applyImport(f, review, key));
        assertEquals(old.generation(), head(f).generation());
        assertEquals(old.physicalId(), head(f).physicalId());
        assertEquals(old.dataVersion(), head(f).dataVersion());
        assertEquals(
            0,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " +
                    new NativeSqlLayout(old).historyTable() +
                    " WHERE deleted=TRUE",
                Integer.class
            )
        );
        assertEquals(
            "READY",
            sql().queryForObject(
                "SELECT status FROM cfg_native_generation WHERE import_job_id=?",
                String.class,
                review.id()
            )
        );
        UUID physical = sql().queryForObject(
            "SELECT physical_id FROM cfg_native_generation WHERE import_job_id=?",
            UUID.class,
            review.id()
        );
        sql().update("DELETE FROM cfg_outbox WHERE id=?", review.id());
        assertEquals("SUCCEEDED", applyImport(f, review, key).status());
        assertEquals(physical, head(f).physicalId());
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_outbox WHERE id=?",
                Integer.class,
                review.id()
            )
        );
    }

    @Test
    void stagedReadyCorruptionCannotBecomeAnActiveGeneration() {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var review = validate(f, document(f, "name,amount\none,1\n", "CSV", "STAGED_REPLACE"), key);
        sql().update(
            "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'CONFLICT','{}')",
            review.id(),
            f.account(),
            review.datasetId()
        );
        assertThrows(VclException.class, () -> applyImport(f, review, key));
        UUID physical = sql().queryForObject(
            "SELECT physical_id FROM cfg_native_generation WHERE import_job_id=?",
            UUID.class,
            review.id()
        );
        var stage = new com.voltcanary.device.configurable.domain.NativeDataset(
            f.account(),
            UUID.randomUUID(),
            2,
            f.dataset().recordIdFieldId(),
            f.dataset().schema(),
            physical
        );
        sql().update(
            "UPDATE " +
                new NativeSqlLayout(stage).table() +
                " SET " +
                NativeSqlLayout.column(NativeFixtures.TEXT) +
                "='corrupted'"
        );
        sql().update("DELETE FROM cfg_outbox WHERE id=?", review.id());
        assertEquals(
            "STAGE_INTEGRITY",
            assertThrows(VclException.class, () -> applyImport(f, review, key)).code()
        );
        assertEquals(f.dataset().generation(), head(f).generation());
    }

    @Test
    void concurrentSameStageAppliesOneGenerationAndPreservesSealedSnapshot() throws Exception {
        var f = fixture(false);
        insert(f, "old-one");
        insert(f, "old-two");
        var original = parser.parse(query(f, 10, null).toString());
        var snapshot = queries(clock).seal(f.account(), f.actor(), original);
        String key = UUID.randomUUID().toString();
        var review = validate(f, document(f, "name,amount\nnew,1\n", "CSV", "STAGED_REPLACE"), key);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> applyImport(f, review, key));
            var two = pool.submit(() -> applyImport(f, review, key));
            assertEquals(one.get(), two.get());
        }
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_native_generation WHERE dataset_id=? AND status='ACTIVE'",
                Integer.class,
                review.datasetId()
            )
        );
        var pinned = query(f, 10, null);
        ((ObjectNode) pinned.path("spec"))
            .putObject("read")
            .put("mode", "SNAPSHOT")
            .put("snapshotId", snapshot.snapshotId().toString());
        var old = queries(clock).query(f.account(), f.actor(), parser.parse(pinned.toString()));
        assertEquals(2, old.rows().size());
        assertEquals(f.dataset().generation(), old.readManifest().generation());
        assertEquals(1, queries(clock).query(f.account(), f.actor(), original).rows().size());
    }

    @Test
    void emptyReplacementShowsAllDeletionsAndRequiresExactReviewConfirmation() {
        var f = fixture(false);
        insert(f, "to-remove");
        String key = UUID.randomUUID().toString();
        var review = validate(f, document(f, "name,amount\n", "CSV", "STAGED_REPLACE"), key);
        assertEquals(1, review.typedDiff().path("deletes").intValue());
        assertEquals(0, review.totalRows());
        assertEquals(
            1,
            queries(clock)
                .query(f.account(), f.actor(), parser.parse(query(f, 10, null).toString()))
                .rows()
                .size()
        );
        assertEquals(
            "REVIEW_CONFLICT",
            assertThrows(VclException.class, () ->
                imports().apply(
                    f.account(),
                    f.actor(),
                    review.datasetId(),
                    review.id(),
                    "0".repeat(64),
                    key
                )
            ).code()
        );
        applyImport(f, review, key);
        assertEquals(
            0,
            queries(clock)
                .query(f.account(), f.actor(), parser.parse(query(f, 10, null).toString()))
                .rows()
                .size()
        );
    }

    @Test
    void referencesAddedAfterPreparationBlockReplacementUntilExplicitlyUnlinked() {
        var target = fixture(false);
        var destination = insert(target, "destination");
        String key = UUID.randomUUID().toString();
        var stage = validate(
            target,
            document(target, "name,amount\nreplacement,1\n", "CSV", "STAGED_REPLACE"),
            key
        );
        var schemaDocument = NativeFixtures.document(UUID.randomUUID(), false);
        ((ObjectNode) schemaDocument.at("/spec/fields/8"))
            .putObject("target")
            .put("kind", "DATASET")
            .put("datasetId", target.dataset().schema().id().toString());
        (
            (com.fasterxml.jackson.databind.node.ArrayNode) schemaDocument.at(
                "/spec/storage/indexedFieldIds"
            )
        ).add(new UUID(0, 9).toString());
        var catalog = new com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog(
            source()
        );
        var schema = com.voltcanary.device.configurable.domain.NativeSchema.compile(
            schemaDocument.toString(),
            com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog.emptyMetadata(
                target.account()
            )
        );
        catalog.register(
            target.account(),
            target.actor(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            schema
        );
        var source = new Fixture(
            target.actor(),
            target.account(),
            catalog.provision(target.account(), schema.id())
        );
        var reference = reserve(source);
        var linked = mutation(source, reference, "INSERT", "linked", null);
        value(linked, 9, "REF").put("value", destination.createdRecordId().toString());
        apply(source, prepare(source, reference, linked));
        assertEquals(
            "ROW_REFERENCED",
            assertThrows(VclException.class, () -> applyImport(target, stage, key)).code()
        );
        assertEquals(target.dataset().generation(), head(target).generation());
        var unlink = reserve(source);
        var cleared = mutation(source, unlink, "UPDATE", "linked", "1");
        value(cleared, 9, "NULL").putNull("value");
        apply(source, prepare(source, unlink, cleared));
        assertEquals("SUCCEEDED", applyImport(target, stage, key).status());
    }

    @Test
    void missingReadyStageIsNotRecreatedAndGenerationPointerDriftFailsClosed() throws Exception {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var stage = validate(f, document(f, "name,amount\none,1\n", "CSV", "STAGED_REPLACE"), key);
        sql().update(
            "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'CONFLICT','{}')",
            stage.id(),
            f.account(),
            stage.datasetId()
        );
        assertThrows(VclException.class, () -> applyImport(f, stage, key));
        UUID physical = sql().queryForObject(
            "SELECT physical_id FROM cfg_native_generation WHERE import_job_id=?",
            UUID.class,
            stage.id()
        );
        String table = "cfg_data_" + physical.toString().replace("-", "");
        sql().execute("DROP TABLE " + table);
        assertEquals(
            "NATIVE_CATALOG_DRIFT",
            assertThrows(VclException.class, () -> applyImport(f, stage, key)).code()
        );
        try (
            var connection = source().getConnection();
            var tables = connection
                .getMetaData()
                .getTables(connection.getCatalog(), connection.getSchema(), table, null)
        ) {
            assertFalse(tables.next());
        }
        sql().update(
            "UPDATE cfg_dataset SET physical_id=? WHERE id=?",
            UUID.randomUUID(),
            f.dataset().schema().id()
        );
        assertEquals(
            "NATIVE_CATALOG_DRIFT",
            assertThrows(VclException.class, () ->
                queries(clock).query(
                    f.account(),
                    f.actor(),
                    parser.parse(query(f, 10, null).toString())
                )
            ).code()
        );
    }

    @Test
    void writerHoldingOldGenerationCommitsBeforeStageAndInvalidatesItsCapturedHead() throws Exception {
        var f = fixture(false);
        String key = UUID.randomUUID().toString();
        var stage = validate(
            f,
            document(f, "name,amount\nreplacement,1\n", "CSV", "STAGED_REPLACE"),
            key
        );
        var writer = reserve(f);
        var prepared = prepare(f, writer, mutation(f, writer, "INSERT", "writer", null));
        reviews().begin(
            f.account(),
            f.actor(),
            prepared.id(),
            prepared.revision(),
            prepared.reviewDigest(),
            prepared.reservationKey()
        );
        var beforeCommit = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var delayed = new org.springframework.jdbc.datasource.DelegatingDataSource(source()) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                var actual = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { java.sql.Connection.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("commit")) {
                            beforeCommit.countDown();
                            if (
                                !release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                            ) throw new java.sql.SQLException("test fence timeout");
                        }
                        try {
                            return method.invoke(actual, args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }
                );
            }
        };
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var write = pool.submit(() ->
                new com.voltcanary.device.configurable.adapter.JdbcNativeRowMutations(
                    delayed,
                    clock
                ).apply(f.account(), f.actor(), prepared.id())
            );
            assertTrue(beforeCommit.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var replace = pool.submit(() -> {
                try {
                    return applyImport(f, stage, key).status();
                } catch (VclException failure) {
                    return failure.code();
                }
            });
            try {
                Thread.sleep(100);
                assertFalse(replace.isDone());
            } finally {
                release.countDown();
            }
            assertEquals("SUCCEEDED", write.get().status());
            assertEquals("VALIDATION_STALE", replace.get());
        } finally {
            release.countDown();
        }
        assertEquals(f.dataset().generation(), head(f).generation());
        assertEquals(2, head(f).dataVersion());
    }

    @Test
    void compositeBusinessKeysRemainDistinctAndStableAcrossReplacement() {
        var owner = fixture(false);
        var schemaDoc = NativeFixtures.document(UUID.randomUUID(), false);
        ((ObjectNode) schemaDoc.at("/spec/fields/3")).put("nullable", false);
        ((com.fasterxml.jackson.databind.node.ArrayNode) schemaDoc.at("/spec/keyFieldIds")).add(
            new UUID(0, 4).toString()
        );
        var schema = com.voltcanary.device.configurable.domain.NativeSchema.compile(
            schemaDoc.toString(),
            com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog.emptyMetadata(
                owner.account()
            )
        );
        var catalog = new com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog(
            source()
        );
        catalog.register(
            owner.account(),
            owner.actor(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            schema
        );
        var f = new Fixture(
            owner.actor(),
            owner.account(),
            catalog.provision(owner.account(), schema.id())
        );
        UUID retained = null;
        for (int code = 1; code <= 2; code++) {
            var reserved = reserve(f);
            var mutation = mutation(f, reserved, "INSERT", "same-name", null);
            ((com.fasterxml.jackson.databind.node.ArrayNode) mutation.at("/spec/key"))
                .addObject()
                .put("fieldId", new UUID(0, 4).toString())
                .putObject("literal")
                .put("type", "INT64")
                .put("value", Integer.toString(code));
            var result = apply(f, prepare(f, reserved, mutation));
            if (code == 2) retained = result.createdRecordId();
        }
        var missing = reserve(f);
        assertEquals(
            "UNIQUE_TARGET_KEY_REQUIRED",
            assertThrows(VclException.class, () ->
                prepare(f, missing, mutation(f, missing, "INSERT", "same-name", null))
            ).code()
        );
        var invalid = schemaDoc.deepCopy();
        ((ObjectNode) invalid.at("/spec/fields/3")).put("nullable", true);
        assertEquals(
            "INVALID_KEY_FIELD",
            assertThrows(VclException.class, () ->
                com.voltcanary.device.configurable.domain.NativeSchema.compile(
                    invalid.toString(),
                    com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog.emptyMetadata(
                        f.account()
                    )
                )
            ).code()
        );
        var doc = document(
            f,
            "name,amount,code\nsame-name,1,2\nnew-name,2,1\n",
            "CSV",
            "STAGED_REPLACE"
        );
        ((com.fasterxml.jackson.databind.node.ArrayNode) doc.at("/spec/keyFields")).add(
            new UUID(0, 4).toString()
        );
        ((com.fasterxml.jackson.databind.node.ArrayNode) doc.at("/spec/format/header")).add("code");
        ((com.fasterxml.jackson.databind.node.ArrayNode) doc.at("/spec/columns"))
            .addObject()
            .put("sourceColumn", "code")
            .put("sourceType", "TEXT")
            .putNull("sourceUnit")
            .put("targetFieldId", new UUID(0, 4).toString())
            .put("emptyValue", "REJECT")
            .putArray("conversions")
            .addObject()
            .put("method", "parse_int64")
            .put("trim", false);
        String key = UUID.randomUUID().toString();
        var review = validate(f, doc, key);
        assertEquals("PREPARED", review.status(), review.errors().toString());
        assertEquals(1, review.typedDiff().path("deletes").intValue());
        var result = applyImport(f, review, key);
        var data = queries(clock).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 10, null).toString())
        );
        UUID stableId = retained;
        assertTrue(
            data
                .rows()
                .stream()
                .anyMatch(
                    row ->
                        row.recordId().equals(stableId) &&
                        row.values().get(new UUID(0, 4)).path("value").asText().equals("2")
                )
        );
        assertEquals(2, result.result().path("rows").size());
    }

    @Test
    void resolvedReferenceUsesExactOwnerIdentityAndSealsItsReadCut() {
        var lookup = fixture(false);
        var destination = insert(lookup, "destination");
        var target = referencing(lookup, lookup);
        String key = UUID.randomUUID().toString();
        var review = validate(
            target,
            referenceDocument(target, lookup, "name,link\nlinked,destination\n", "ATOMIC_INSERT"),
            key
        );
        assertEquals("PREPARED", review.status(), review.errors().toString());
        assertEquals(1, review.typedDiff().path("referenceReadSet").size());
        assertEquals(1, review.typedDiff().at("/referenceReadSet/0/resolvedRecords").intValue());
        assertFalse(review.typedDiff().toString().contains("destination"));
        var applied = applyImport(target, review, key);
        assertEquals(applied, applyImport(target, review, key));
        var row = queries(clock)
            .query(
                target.account(),
                target.actor(),
                parser.parse(query(target, 10, null).toString())
            )
            .rows()
            .getFirst();
        assertEquals(
            destination.createdRecordId().toString(),
            row.values().get(new UUID(0, 9)).path("value").asText()
        );
    }

    @Test
    void missingReferencesCountEveryInvalidRowAndNullDoesNotGuessAnIdentity() {
        var lookup = fixture(false);
        var target = referencing(lookup, lookup);
        var missing = validate(
            target,
            referenceDocument(
                target,
                lookup,
                "name,link\none,private-absent\ntwo,private-absent\n",
                "ATOMIC_INSERT"
            ),
            UUID.randomUUID().toString()
        );
        assertEquals("REJECTED", missing.status());
        assertEquals(2, missing.invalidRows());
        assertEquals("REF_NOT_FOUND", missing.errors().get(0).path("code").asText());
        assertFalse(missing.errors().toString().contains("private-absent"));
        var nullable = referenceDocument(target, lookup, "name,link\nnullable,\n", "ATOMIC_INSERT");
        ((ObjectNode) nullable.at("/spec/columns/1")).put("emptyValue", "AS_NULL");
        String key = UUID.randomUUID().toString();
        assertEquals(
            "SUCCEEDED",
            applyImport(target, validate(target, nullable, key), key).status()
        );
        var row = queries(clock)
            .query(
                target.account(),
                target.actor(),
                parser.parse(query(target, 10, null).toString())
            )
            .rows()
            .getFirst();
        assertEquals("NULL", row.values().get(new UUID(0, 9)).path("type").asText());
        var another = referenceDocument(
            target,
            lookup,
            "name,link\nanother-null,\n",
            "ATOMIC_INSERT"
        );
        ((ObjectNode) another.at("/spec/columns/1")).put("emptyValue", "AS_NULL");
        String nextKey = UUID.randomUUID().toString();
        var prepared = validate(target, another, nextKey);
        assertEquals(1, prepared.typedDiff().path("referenceReadSet").size());
        assertEquals(0, prepared.typedDiff().at("/referenceReadSet/0/resolvedRecords").asInt());
        insert(lookup, "new-source-row");
        assertEquals(
            "VALIDATION_STALE",
            assertThrows(VclException.class, () -> applyImport(target, prepared, nextKey)).code()
        );
    }

    @Test
    void changedReferenceHeadInvalidatesApprovalEvenWhenTheResolvedIdStillExists() {
        var lookup = fixture(false);
        var destination = insert(lookup, "destination");
        var target = referencing(lookup, lookup);
        String key = UUID.randomUUID().toString();
        var review = validate(
            target,
            referenceDocument(target, lookup, "name,link\nlinked,destination\n", "ATOMIC_INSERT"),
            key
        );
        var edit = reserve(lookup);
        var document = mutation(lookup, edit, "UPDATE", "destination", "1");
        value(document, 2, "DECIMAL").put("unit", "V").put("value", "2");
        apply(lookup, prepare(lookup, edit, document));
        assertEquals(
            "VALIDATION_STALE",
            assertThrows(VclException.class, () -> applyImport(target, review, key)).code()
        );
        assertEquals(1, head(target).dataVersion());
        assertEquals(
            0,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_outbox WHERE id=?",
                Integer.class,
                review.id()
            )
        );
        assertEquals(
            destination.createdRecordId(),
            queries(clock)
                .query(
                    lookup.account(),
                    lookup.actor(),
                    parser.parse(query(lookup, 10, null).toString())
                )
                .rows()
                .getFirst()
                .recordId()
        );
    }

    @Test
    void foreignReferenceOwnerAndMismatchedDeclaredTargetAreDeniedBeforeWrites() {
        var lookup = fixture(false);
        insert(lookup, "destination");
        var target = referencing(lookup, lookup);
        var foreign = fixture(false);
        insert(foreign, "destination");
        var foreignDoc = referenceDocument(
            target,
            foreign,
            "name,link\nlinked,destination\n",
            "ATOMIC_INSERT"
        );
        assertEquals(
            "DATASET_UNAVAILABLE",
            assertThrows(VclException.class, () ->
                validate(target, foreignDoc, UUID.randomUUID().toString())
            ).code()
        );
        var another = referencing(lookup, lookup);
        insert(another, "destination");
        var wrongTarget = referenceDocument(
            target,
            another,
            "name,link\nlinked,destination\n",
            "ATOMIC_INSERT"
        );
        assertEquals(
            "REF_TYPE_MISMATCH",
            assertThrows(VclException.class, () ->
                validate(target, wrongTarget, UUID.randomUUID().toString())
            ).code()
        );
        assertEquals(1, head(target).dataVersion());
    }

    @Test
    void repeatedReferenceKeysUseOneBatchPerOwnerAndNoPerRowAuthorizationLookups() {
        var lookup = fixture(false);
        insert(lookup, "destination");
        var target = referencing(lookup, lookup);
        String table = new NativeSqlLayout(head(lookup)).table();
        var batches = new java.util.concurrent.atomic.AtomicInteger();
        var points = new java.util.concurrent.atomic.AtomicInteger();
        var observed = new org.springframework.jdbc.datasource.DelegatingDataSource(source()) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                var actual = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { java.sql.Connection.class },
                    (proxy, method, args) -> {
                        if (
                            method.getName().equals("prepareStatement") &&
                            args[0] instanceof String sql &&
                            sql.contains(table)
                        ) {
                            if (sql.contains("external_key IN (")) batches.incrementAndGet();
                            if (sql.startsWith("SELECT 1 FROM")) points.incrementAndGet();
                        }
                        try {
                            return method.invoke(actual, args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }
                );
            }
        };
        var content = new StringBuilder("name,link\n");
        for (int i = 0; i < 25; i++) content.append("row-").append(i).append(",destination\n");
        var imports = new JdbcNativeImports(observed, clock);
        String key = UUID.randomUUID().toString();
        var doc = referenceDocument(target, lookup, content.toString(), "ATOMIC_INSERT");
        var review = imports.validate(
            target.account(),
            target.actor(),
            target.dataset().schema().id(),
            key,
            parser.parse(doc.toString())
        );
        assertEquals("PREPARED", review.status(), review.errors().toString());
        assertEquals(1, batches.get());
        assertEquals(
            "SUCCEEDED",
            imports
                .apply(
                    target.account(),
                    target.actor(),
                    target.dataset().schema().id(),
                    review.id(),
                    review.reviewDigest(),
                    key
                )
                .status()
        );
        assertEquals(2, batches.get());
        assertEquals(0, points.get());
    }

    @Test
    void orderedReferenceChainsResolveBothOwnersAndRetainBothPins() {
        var first = fixture(false);
        var original = insert(first, "external-key");
        // A second collection's declared unique key is an inline REF to the first owner.
        var schemaDoc = NativeFixtures.document(UUID.randomUUID(), false);
        ((ObjectNode) schemaDoc.at("/spec/fields/8"))
            .put("nullable", false)
            .putObject("target")
            .put("kind", "DATASET")
            .put("datasetId", first.dataset().schema().id().toString());
        ((ObjectNode) schemaDoc.path("spec"))
            .putArray("keyFieldIds")
            .add(new UUID(0, 9).toString());
        var catalog = new com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog(
            source()
        );
        var schema = com.voltcanary.device.configurable.domain.NativeSchema.compile(
            schemaDoc.toString(),
            com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog.emptyMetadata(
                first.account()
            )
        );
        catalog.register(
            first.account(),
            first.actor(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            schema
        );
        var second = new Fixture(
            first.actor(),
            first.account(),
            catalog.provision(first.account(), schema.id())
        );
        var reserved = reserve(second);
        var record = mutation(second, reserved, "INSERT", "unused", null);
        ((ObjectNode) record.path("spec"))
            .putArray("key")
            .addObject()
            .put("fieldId", new UUID(0, 9).toString())
            .putObject("literal")
            .put("type", "REF")
            .put("value", original.createdRecordId().toString());
        value(record, 1, "TEXT").put("value", "bound-key");
        var destination = apply(second, prepare(second, reserved, record));
        var target = referencing(first, second);
        var doc = referenceDocument(
            target,
            first,
            "name,link\nchained,external-key\n",
            "ATOMIC_INSERT"
        );
        var conversion = (
            (com.fasterxml.jackson.databind.node.ArrayNode) doc.at("/spec/columns/1/conversions")
        )
            .addObject()
            .put("method", "resolve_ref")
            .put("keyFieldId", new UUID(0, 9).toString())
            .put("cardinality", "EXACT_ONE");
        conversion
            .putObject("target")
            .put("datasetId", second.dataset().schema().id().toString())
            .put("schemaRevision", 1);
        String key = UUID.randomUUID().toString();
        var review = validate(target, doc, key);
        assertEquals("PREPARED", review.status(), review.errors().toString());
        assertEquals(2, review.typedDiff().path("referenceReadSet").size());
        applyImport(target, review, key);
        var row = queries(clock)
            .query(
                target.account(),
                target.actor(),
                parser.parse(query(target, 10, null).toString())
            )
            .rows()
            .getFirst();
        assertEquals(
            destination.createdRecordId().toString(),
            row.values().get(new UUID(0, 9)).path("value").asText()
        );
    }

    @Test
    void readyReferenceStageCannotCutOverAfterReferenceGenerationReplacement() {
        var lookup = fixture(false);
        var destination = insert(lookup, "destination");
        var target = referencing(lookup, lookup);
        String key = UUID.randomUUID().toString();
        var review = validate(
            target,
            referenceDocument(target, lookup, "name,link\nlinked,destination\n", "STAGED_REPLACE"),
            key
        );
        assertEquals("PREPARED", review.status(), review.errors().toString());
        sql().update(
            "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'CONFLICT','{}')",
            review.id(),
            target.account(),
            review.datasetId()
        );
        assertThrows(VclException.class, () -> applyImport(target, review, key));
        assertEquals(
            "READY",
            sql().queryForObject(
                "SELECT status FROM cfg_native_generation WHERE import_job_id=?",
                String.class,
                review.id()
            )
        );
        String replacementKey = UUID.randomUUID().toString();
        var replacement = validate(
            lookup,
            document(lookup, "name,amount\ndestination,1\n", "CSV", "STAGED_REPLACE"),
            replacementKey
        );
        applyImport(lookup, replacement, replacementKey);
        assertEquals(
            destination.createdRecordId(),
            queries(clock)
                .query(
                    lookup.account(),
                    lookup.actor(),
                    parser.parse(query(lookup, 10, null).toString())
                )
                .rows()
                .getFirst()
                .recordId()
        );
        sql().update("DELETE FROM cfg_outbox WHERE id=?", review.id());
        assertEquals(
            "VALIDATION_STALE",
            assertThrows(VclException.class, () -> applyImport(target, review, key)).code()
        );
        assertEquals(target.dataset().generation(), head(target).generation());
    }

    @Test
    void modifiedManifestAndChecksumCannotReuseTheOriginalApprovalDigest() throws Exception {
        var lookup = fixture(false);
        insert(lookup, "destination");
        var target = referencing(lookup, lookup);
        String key = UUID.randomUUID().toString();
        var review = validate(
            target,
            referenceDocument(target, lookup, "name,link\nlinked,destination\n", "ATOMIC_INSERT"),
            key
        );
        String original = sql().queryForObject(
            "SELECT canonical_plan FROM cfg_import_job WHERE id=?",
            String.class,
            review.id()
        );
        var plan = NativeFixtures.MAPPER.readTree(original);
        ((ObjectNode) plan.at("/0/references/0")).put("schemaDigest", "0".repeat(64));
        String changed = com.voltcanary.vcl.VclCanonical.encode(plan);
        sql().update(
            "UPDATE cfg_import_job SET canonical_plan=?,plan_digest=? WHERE id=?",
            changed,
            com.voltcanary.vcl.VclCanonical.sha256(changed),
            review.id()
        );
        assertEquals(
            "IMPORT_INTEGRITY",
            assertThrows(VclException.class, () -> applyImport(target, review, key)).code()
        );
        assertEquals(
            "IMPORT_INTEGRITY",
            assertThrows(VclException.class, () ->
                imports().inspect(target.account(), target.actor(), review.datasetId(), review.id())
            ).code()
        );
        assertEquals(1, head(target).dataVersion());
    }

    @Test
    void referenceVersionsAboveTheJsonSafeIntegerRangeRemainExact() {
        var lookup = fixture(false);
        var destination = insert(lookup, "destination");
        long version = 9_007_199_254_740_992L;
        sql().update(
            "UPDATE " +
                new NativeSqlLayout(head(lookup)).table() +
                " SET record_version=? WHERE record_id=?",
            version,
            destination.createdRecordId()
        );
        var target = referencing(lookup, lookup);
        String key = UUID.randomUUID().toString();
        var review = validate(
            target,
            referenceDocument(target, lookup, "name,link\nlinked,destination\n", "ATOMIC_INSERT"),
            key
        );
        assertEquals("PREPARED", review.status(), review.errors().toString());
        assertEquals("SUCCEEDED", applyImport(target, review, key).status());
    }
}
