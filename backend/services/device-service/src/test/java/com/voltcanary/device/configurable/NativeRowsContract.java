package com.voltcanary.device.configurable;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowMutations;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowQueries;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowReviews;
import com.voltcanary.device.configurable.adapter.NativeQueryCursor;
import com.voltcanary.device.configurable.adapter.NativeSqlLayout;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class NativeRowsContract {

    abstract DataSource source();

    final Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
    final VclParser parser = new VclParser();

    record Fixture(UUID actor, UUID account, NativeDataset dataset) {}

    Fixture fixture(boolean history) {
        UUID actor = UUID.randomUUID(),
            account = UUID.randomUUID();
        sql().update(
            "INSERT INTO accounts(id,user_id,status) VALUES(?,?,'ACTIVE')",
            account,
            actor
        );
        var catalog = new JdbcNativeDatasetCatalog(source());
        var schema = NativeFixtures.schema(account, UUID.randomUUID(), history);
        catalog.register(account, actor, UUID.randomUUID(), UUID.randomUUID(), schema);
        return new Fixture(actor, account, catalog.provision(account, schema.id()));
    }

    JdbcTemplate sql() {
        return new JdbcTemplate(source());
    }

    JdbcNativeRowReviews reviews() {
        return new JdbcNativeRowReviews(source(), clock);
    }

    JdbcNativeRowMutations mutations() {
        return new JdbcNativeRowMutations(source(), clock);
    }

    JdbcNativeRowQueries queries(Clock now) {
        return new JdbcNativeRowQueries(source(), new NativeQueryCursor(new byte[32], now));
    }

    NativeDataset head(Fixture f) {
        return new JdbcNativeDatasetCatalog(source())
            .ready(f.account(), f.dataset().schema().id(), 1)
            .orElseThrow();
    }

    NativeRowReview reserve(Fixture f) {
        return reviews().reserve(
            f.account(),
            f.actor(),
            f.dataset().schema().id(),
            1,
            UUID.randomUUID().toString()
        );
    }

    ObjectNode mutation(
        Fixture f,
        NativeRowReview review,
        String operation,
        String key,
        String version
    ) {
        var root = NativeFixtures.MAPPER.createObjectNode()
            .put("language", "VCL/1")
            .put("kind", "MUTATION");
        var spec = root
            .putObject("spec")
            .put("mutationId", review.mutationId().toString())
            .put("reviewId", review.id().toString())
            .put("operation", operation)
            .put("idempotencyKey", review.reservationKey());
        if (version == null) spec.putNull("expectedRowVersion");
        else spec.put("expectedRowVersion", version);
        spec.putObject("target")
            .put("datasetId", review.datasetId().toString())
            .put("schemaRevision", 1);
        var current = head(f);
        spec.putObject("targetPrecondition")
            .put("mode", "NATIVE")
            .put("generation", current.generation().toString())
            .put("dataVersion", current.dataVersion());
        spec.putArray("key")
            .addObject()
            .put("fieldId", NativeFixtures.TEXT.toString())
            .putObject("literal")
            .put("type", "TEXT")
            .put("value", key);
        spec.putArray("values");
        return root;
    }

    ObjectNode value(ObjectNode root, long id, String type) {
        return ((com.fasterxml.jackson.databind.node.ArrayNode) root.at("/spec/values"))
            .addObject()
            .put("fieldId", new UUID(0, id).toString())
            .putObject("literal")
            .put("type", type);
    }

    NativeRowReview prepare(Fixture f, NativeRowReview review, ObjectNode document) {
        return reviews().prepare(
            f.account(),
            f.actor(),
            review.id(),
            parser.parse(document.toString())
        );
    }

    NativeRowReview apply(Fixture f, NativeRowReview review) {
        reviews().begin(
            f.account(),
            f.actor(),
            review.id(),
            review.revision(),
            review.reviewDigest(),
            review.reservationKey()
        );
        return mutations().apply(f.account(), f.actor(), review.id());
    }

    NativeRowReview insert(Fixture f, String key) {
        var review = reserve(f);
        return apply(f, prepare(f, review, mutation(f, review, "INSERT", key, null)));
    }

    @Test
    void historicalCutRetainsPreDeleteRowAndDetectsMissingCheckpoint() {
        var f = fixture(true);
        insert(f, "archived row");
        var captured = head(f);
        var query = parser.parse(query(f, 10, null).toString());
        var history = new com.voltcanary.device.configurable.adapter.JdbcNativeHistoryRows(
            source(),
            new com.voltcanary.device.configurable.adapter.NativeQueryCursor(new byte[32], clock),
            null
        );
        var removed = reserve(f);
        apply(f, prepare(f, removed, mutation(f, removed, "DELETE", "archived row", "1")));
        var old = history.query(
            f.account(),
            f.actor(),
            captured.generation(),
            captured.dataVersion(),
            query
        );
        assertEquals("HISTORY", old.readManifest().consistency());
        assertEquals(
            "archived row",
            old.rows().getFirst().values().get(NativeFixtures.TEXT).path("value").asText()
        );
        assertTrue(
            history
                .query(f.account(), f.actor(), captured.generation(), head(f).dataVersion(), query)
                .rows()
                .isEmpty()
        );
        sql().update(
            "DELETE FROM cfg_native_history_checkpoint WHERE dataset_id=? AND generation=? AND data_version=?",
            captured.schema().id(),
            captured.generation(),
            captured.dataVersion()
        );
        assertEquals(
            "HISTORY_INTEGRITY",
            assertThrows(VclException.class, () ->
                history.query(
                    f.account(),
                    f.actor(),
                    captured.generation(),
                    captured.dataVersion(),
                    query
                )
            ).code()
        );
    }

    ObjectNode query(Fixture f, int limit, String cursor) {
        var root = NativeFixtures.MAPPER.createObjectNode()
            .put("language", "VCL/1")
            .put("kind", "QUERY");
        var spec = root.putObject("spec").put("limit", limit);
        spec.putObject("target")
            .put("datasetId", f.dataset().schema().id().toString())
            .put("schemaRevision", 1);
        var select = spec.putArray("select");
        f.dataset()
            .schema()
            .fields()
            .forEach(field -> select.add(field.path("id").asText()));
        spec.putObject("where")
            .put("op", "CONST")
            .putObject("literal")
            .put("type", "BOOLEAN")
            .put("value", true);
        spec.putArray("order")
            .addObject()
            .put("fieldId", f.dataset().recordIdFieldId().toString())
            .put("direction", "ASC");
        if (cursor == null) spec.putNull("cursor");
        else spec.put("cursor", cursor);
        spec.putObject("read").put("mode", "LIVE");
        return root;
    }

    private com.voltcanary.vcl.VclDocument snapshotQuery(
        Fixture f,
        int limit,
        UUID snapshotId,
        String cursor
    ) {
        var document = query(f, limit, cursor);
        ((ObjectNode) document.path("spec"))
            .putObject("read")
            .put("mode", "SNAPSHOT")
            .put("snapshotId", snapshotId.toString());
        return parser.parse(document.toString());
    }

    @Test
    void reviewedSchemaChangePreservesIdentityAndPinnedSnapshotSchema() {
        var f = fixture(true);
        insert(f, "retained");
        var before = head(f);
        var query = parser.parse(query(f, 10, null).toString());
        var snapshot = queries(clock).seal(f.account(), f.actor(), query);
        var pinnedQuery = snapshotQuery(f, 10, snapshot.snapshotId(), null);
        var original = queries(clock).query(f.account(), f.actor(), pinnedQuery);

        var migrations = new com.voltcanary.device.configurable.adapter.JdbcNativeSchemaMigrations(
            source(),
            clock,
            NativeRowsContract::emptyDependencyCuts
        );
        UUID dataset = before.schema().id();
        String key = UUID.randomUUID().toString();
        var reserved = migrations.reserve(f.account(), f.actor(), dataset, key, List.of());
        UUID review = UUID.fromString(reserved.path("id").asText());
        var schema = NativeFixtures.document(dataset, true);
        var spec = (ObjectNode) schema.path("spec");
        spec.put("revision", 2).put("label", "Renamed collection");
        ((ObjectNode) spec.path("storage"))
            .putArray("indexedFieldIds")
            .add(NativeFixtures.TEXT.toString());
        var request = NativeFixtures.MAPPER.createObjectNode().put("dataMode", "PRESERVE");
        request.set("schema", schema);
        request.putArray("mappings");
        var prepared = migrations.prepare(f.account(), f.actor(), dataset, review, request);
        assertEquals("PREPARED", prepared.path("status").asText(), prepared.toString());
        assertTrue(prepared.at("/impact/indexesChanged").booleanValue());
        long revision = prepared.path("reviewRevision").longValue();
        String digest = prepared.path("reviewDigest").asText();
        var applied = migrations.apply(
            f.account(),
            f.actor(),
            dataset,
            review,
            revision,
            digest,
            key
        );
        assertEquals("SUCCEEDED", applied.path("status").asText(), applied.toString());
        assertEquals(
            applied,
            migrations.apply(f.account(), f.actor(), dataset, review, revision, digest, key)
        );
        assertEquals(original, queries(clock).query(f.account(), f.actor(), pinnedQuery));

        var current = new JdbcNativeDatasetCatalog(source())
            .current(f.account(), dataset)
            .orElseThrow();
        assertEquals(2, current.schema().revision());
        assertNotEquals(before.generation(), current.generation());
        var live = query(f, 10, null);
        ((ObjectNode) live.at("/spec/target")).put("schemaRevision", 2);
        var row = queries(clock)
            .query(f.account(), f.actor(), parser.parse(live.toString()))
            .rows()
            .getFirst();
        assertEquals(original.rows().getFirst().recordId(), row.recordId());
        assertEquals("2", row.recordVersion());
    }

    private static com.fasterxml.jackson.databind.JsonNode emptyDependencyCuts(
        UUID account,
        UUID actor,
        UUID dataset,
        long revision
    ) {
        var cuts = NativeFixtures.MAPPER.createArrayNode();
        for (String owner : List.of("analytics", "alerts", "dashboards")) {
            var empty = cuts
                .addObject()
                .put("owner", owner)
                .put("accountId", account.toString())
                .put("datasetId", dataset.toString())
                .put("schemaRevision", revision)
                .put("complete", true)
                .put("cutDigest", com.voltcanary.vcl.VclCanonical.sha256("[]"));
            empty.putArray("dependencies");
        }
        return cuts;
    }

    @Test
    void sealedSnapshotPaginatesOriginalRowsAfterWritesAndRejectsCrossSnapshotCursor() {
        var f = fixture(false);
        insert(f, "one");
        insert(f, "two");
        var original = queries(clock).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 10, null).toString())
        );
        var template = parser.parse(query(f, 1, null).toString());
        var sealed = queries(clock).seal(f.account(), f.actor(), template);
        assertEquals(2, sealed.rowCount());
        var first = queries(clock).query(
            f.account(),
            f.actor(),
            snapshotQuery(f, 1, sealed.snapshotId(), null)
        );
        assertEquals(sealed.snapshotId(), first.readManifest().snapshotId());
        assertEquals("PARTIAL", first.completeness());
        insert(f, "three");
        var second = queries(clock).query(
            f.account(),
            f.actor(),
            snapshotQuery(f, 1, sealed.snapshotId(), first.nextCursor())
        );
        assertEquals("COMPLETE", second.completeness());
        assertEquals(first.readManifest(), second.readManifest());
        assertEquals(original.rows(), List.of(first.rows().getFirst(), second.rows().getFirst()));
        var newer = queries(clock).seal(f.account(), f.actor(), template);
        assertEquals(
            "CURSOR_CONFLICT",
            assertThrows(VclException.class, () ->
                queries(clock).query(
                    f.account(),
                    f.actor(),
                    snapshotQuery(f, 1, newer.snapshotId(), first.nextCursor())
                )
            ).code()
        );
        assertEquals(
            "SNAPSHOT_CONFLICT",
            assertThrows(VclException.class, () ->
                queries(clock).query(
                    f.account(),
                    f.actor(),
                    snapshotQuery(f, 2, sealed.snapshotId(), null)
                )
            ).code()
        );
    }

    @Test
    void sealedSnapshotEnforcesExpiryQuotaIntegrityAndCurrentAuthority() {
        var f = fixture(false);
        var template = parser.parse(query(f, 1, null).toString());
        var sealed = queries(clock).seal(f.account(), f.actor(), template);
        var document = snapshotQuery(f, 1, sealed.snapshotId(), null);
        for (int i = 1; i < 8; i++) queries(clock).seal(f.account(), f.actor(), template);
        assertEquals(
            "SNAPSHOT_QUOTA",
            assertThrows(VclException.class, () ->
                queries(clock).seal(f.account(), f.actor(), template)
            ).code()
        );
        var expired = queries(Clock.offset(clock, Duration.ofMinutes(10)));
        assertEquals(
            "SNAPSHOT_EXPIRED",
            assertThrows(VclException.class, () ->
                expired.query(f.account(), f.actor(), document)
            ).code()
        );
        assertEquals(0, expired.seal(f.account(), f.actor(), template).rowCount());
        var active = queries(clock).seal(f.account(), f.actor(), template);
        documentIntegrityAndRevocation(f, active.snapshotId());
    }

    private void documentIntegrityAndRevocation(Fixture f, UUID id) {
        var document = snapshotQuery(f, 1, id, null);
        assertEquals(
            "COMPLETE",
            queries(clock).query(f.account(), f.actor(), document).completeness()
        );
        sql().update(
            "UPDATE cfg_native_query_snapshot SET canonical_rows='{}' WHERE snapshot_id=?",
            id
        );
        assertEquals(
            "SNAPSHOT_INTEGRITY",
            assertThrows(VclException.class, () ->
                queries(clock).query(f.account(), f.actor(), document)
            ).code()
        );
        sql().update("UPDATE accounts SET status='DELETED' WHERE id=?", f.account());
        assertEquals(
            "ACCOUNT_ACCESS_REVOKED",
            assertThrows(VclException.class, () ->
                queries(clock).query(f.account(), f.actor(), document)
            ).code()
        );
    }

    @Test
    void sealedAsOfUsesHalfOpenEffectiveTimeAndPreservesTheRecordedCut() {
        var f = fixture(true);
        UUID interval = new UUID(0, 10);
        UUID keyField = new UUID(0, 3);
        var key = java.util.Map.<UUID, com.fasterxml.jackson.databind.JsonNode>of(
            keyField,
            NativeFixtures.MAPPER.createObjectNode().put("type", "BOOLEAN").put("value", true)
        );
        Instant start = Instant.parse("2026-11-03T01:00:00Z");
        Instant boundary = start.plusSeconds(3600);
        var row = reserve(f);
        var insert = mutation(f, row, "INSERT", "first assignment", null);
        value(insert, 3, "BOOLEAN").put("value", true);
        value(insert, 10, "INTERVAL")
            .putObject("value")
            .put("start", start.toString())
            .put("end", boundary.toString());
        apply(f, prepare(f, row, insert));

        var template = parser.parse(query(f, 10, null).toString());
        var projection =
            new com.voltcanary.device.configurable.port.NativeRowQueries.AsOfProjection(
                interval,
                List.of(keyField)
            );
        var unindexed = queries(clock).seal(f.account(), f.actor(), template);
        assertEquals(
            "ASOF_PROJECTION_CONFLICT",
            assertThrows(VclException.class, () ->
                queries(clock).asOf(
                    f.account(),
                    f.actor(),
                    snapshotQuery(f, 10, unindexed.snapshotId(), null),
                    interval,
                    start,
                    key
                )
            ).code()
        );
        var older = queries(clock).seal(f.account(), f.actor(), template, projection);
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_native_snapshot_interval WHERE snapshot_id=?",
                Integer.class,
                older.snapshotId()
            )
        );
        var oldQuery = snapshotQuery(f, 10, older.snapshotId(), null);
        assertEquals(
            "KNOWN",
            queries(clock).asOf(f.account(), f.actor(), oldQuery, interval, start, key).status()
        );
        assertEquals(
            "ASOF_MISSING",
            queries(clock).asOf(f.account(), f.actor(), oldQuery, interval, boundary, key).reason()
        );
        var originalSplit = queries(clock).splitAsOf(
            f.account(),
            f.actor(),
            oldQuery,
            interval,
            start.minusSeconds(1),
            boundary.plusSeconds(1),
            key
        );
        assertEquals(
            originalSplit.readManifest(),
            queries(clock).inspectAsOf(f.account(), f.actor(), oldQuery, projection)
        );
        assertEquals(
            List.of("UNKNOWN", "KNOWN", "UNKNOWN"),
            originalSplit
                .segments()
                .stream()
                .map(segment -> segment.status())
                .toList()
        );
        assertEquals(start, originalSplit.segments().get(1).start());
        assertEquals(boundary, originalSplit.segments().get(1).end());

        var second = reserve(f);
        var correction = mutation(f, second, "INSERT", "late assignment", null);
        value(correction, 3, "BOOLEAN").put("value", true);
        value(correction, 10, "INTERVAL")
            .putObject("value")
            .put("start", start.minusSeconds(1).toString())
            .putNull("end");
        apply(f, prepare(f, second, correction));
        var newer = queries(clock).seal(f.account(), f.actor(), template, projection);
        var newQuery = snapshotQuery(f, 10, newer.snapshotId(), null);
        var ambiguous = queries(clock).asOf(f.account(), f.actor(), newQuery, interval, start, key);
        assertEquals("ASOF_AMBIGUOUS", ambiguous.reason());
        assertNull(ambiguous.row());
        assertEquals(
            "KNOWN",
            queries(clock).asOf(f.account(), f.actor(), newQuery, interval, boundary, key).status()
        );
        var correctedSplit = queries(clock).splitAsOf(
            f.account(),
            f.actor(),
            newQuery,
            interval,
            start.minusSeconds(1),
            boundary.plusSeconds(1),
            key
        );
        assertEquals(
            List.of("KNOWN", "UNKNOWN", "KNOWN"),
            correctedSplit
                .segments()
                .stream()
                .map(segment -> segment.status())
                .toList()
        );
        assertEquals("ASOF_AMBIGUOUS", correctedSplit.segments().get(1).reason());
        assertEquals(
            originalSplit,
            queries(clock).splitAsOf(
                f.account(),
                f.actor(),
                oldQuery,
                interval,
                start.minusSeconds(1),
                boundary.plusSeconds(1),
                key
            )
        );
        assertEquals(
            "KNOWN",
            queries(clock).asOf(f.account(), f.actor(), oldQuery, interval, start, key).status()
        );
        assertEquals(
            "ASOF_MISSING",
            queries(clock).asOf(f.account(), f.actor(), oldQuery, interval, boundary, key).reason()
        );
        assertEquals(
            "ASOF_FIELDS_INVALID",
            assertThrows(VclException.class, () ->
                queries(clock).asOf(f.account(), f.actor(), newQuery, keyField, start, key)
            ).code()
        );
    }

    @Test
    void typedRoundTripOmissionExplicitNullAndDenseHistory() {
        var f = fixture(true);
        var reserved = reserve(f);
        var document = mutation(f, reserved, "INSERT", "Cafe\u0301", null);
        value(document, 2, "DECIMAL").put("unit", "V").put("value", "123.456");
        value(document, 3, "BOOLEAN").put("value", true);
        value(document, 4, "INT64").put("value", Long.toString(Long.MAX_VALUE));
        value(document, 5, "DATE").put("value", "2026-11-03");
        value(document, 6, "INSTANT").put("value", "2026-11-03T00:00:00.123456Z");
        value(document, 7, "DURATION").put("value", "123456789");
        value(document, 8, "POINT").putObject("value").put("longitude", 20.4).put("latitude", 44.8);
        value(document, 10, "INTERVAL")
            .putObject("value")
            .put("start", "2026-11-03T00:00:00Z")
            .putNull("end");
        value(document, 11, "ENUM").put("value", "OPEN");
        var success = apply(f, prepare(f, reserved, document));
        assertEquals("SUCCEEDED", success.status());
        assertEquals(success, apply(f, success));
        var row = queries(clock)
            .query(f.account(), f.actor(), parser.parse(query(f, 10, null).toString()))
            .rows()
            .getFirst();
        assertEquals(reserved.createdRecordId(), row.recordId());
        assertEquals("Café", row.values().get(NativeFixtures.TEXT).path("value").asText());
        assertEquals("123.456", row.values().get(NativeFixtures.AMOUNT).path("value").asText());
        assertEquals(
            Long.toString(Long.MAX_VALUE),
            row.values().get(new UUID(0, 4)).path("value").asText()
        );
        assertEquals(
            "2026-11-03T00:00:00.123456Z",
            row.values().get(new UUID(0, 6)).path("value").asText()
        );
        assertEquals("NULL", row.values().get(new UUID(0, 9)).path("type").asText());
        var update = reserve(f);
        var edit = mutation(f, update, "UPDATE", "Café", "1");
        value(edit, 2, "NULL").putNull("value");
        apply(f, prepare(f, update, edit));
        row = queries(clock)
            .query(f.account(), f.actor(), parser.parse(query(f, 10, null).toString()))
            .rows()
            .getFirst();
        assertEquals("2", row.recordVersion());
        assertEquals("NULL", row.values().get(NativeFixtures.AMOUNT).path("type").asText());
        assertEquals("OPEN", row.values().get(new UUID(0, 11)).path("value").asText());
        var deletion = reserve(f);
        apply(f, prepare(f, deletion, mutation(f, deletion, "DELETE", "Café", "2")));
        String history = NativeSqlLayout.identifier(
            new NativeSqlLayout(f.dataset().schema()).historyTable()
        );
        assertEquals(3, sql().queryForObject("SELECT COUNT(*) FROM " + history, Integer.class));
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM " + history + " WHERE deleted=TRUE AND record_version=3",
                Integer.class
            )
        );
        assertEquals(4L, head(f).dataVersion());
        assertEquals(
            3,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_row_receipt WHERE account_id=?",
                Integer.class,
                f.account()
            )
        );
    }

    @Test
    void concurrentPreparedWritersHaveOneWinnerAndReceiptReplayIsExact() throws Exception {
        var f = fixture(true);
        insert(f, "key");
        var a = reserve(f);
        var b = reserve(f);
        var editA = mutation(f, a, "UPDATE", "key", "1");
        value(editA, 2, "DECIMAL").put("unit", "V").put("value", "1");
        a = prepare(f, a, editA);
        var editB = mutation(f, b, "UPDATE", "key", "1");
        value(editB, 2, "DECIMAL").put("unit", "V").put("value", "2");
        b = prepare(f, b, editB);
        var intents = List.of(a, b);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(
                intents
                    .stream()
                    .<java.util.concurrent.Callable<String>>map(review -> () -> {
                        try {
                            return apply(f, review).status();
                        } catch (VclException conflict) {
                            return conflict.code();
                        }
                    })
                    .toList()
            );
            var statuses = List.of(results.get(0).get(), results.get(1).get());
            assertEquals(1, statuses.stream().filter("SUCCEEDED"::equals).count());
            assertTrue(statuses.contains("TARGET_CONFLICT"), statuses.toString());
        }
        var winner = reviews().inspect(f.account(), f.actor(), a.id()).status().equals("SUCCEEDED")
            ? a
            : b;
        var expected = mutations().apply(f.account(), f.actor(), winner.id());
        assertEquals(
            expected.resultJson(),
            mutations().apply(f.account(), f.actor(), winner.id()).resultJson()
        );
        assertEquals(3L, head(f).dataVersion());
        assertEquals(
            2,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_row_receipt WHERE account_id=?",
                Integer.class,
                f.account()
            )
        );
    }

    @Test
    void outboxFailureRollsBackRowHistoryReceiptAndHeadTogether() {
        var f = fixture(true);
        var reserved = reserve(f);
        var prepared = prepare(f, reserved, mutation(f, reserved, "INSERT", "atomic", null));
        String constraint = "reject_" + reserved.id().toString().replace("-", "");
        sql().execute(
            "ALTER TABLE cfg_outbox ADD CONSTRAINT " +
                constraint +
                " CHECK(id<>'" +
                reserved.receiptId() +
                "')"
        );
        try {
            assertThrows(VclException.class, () -> apply(f, prepared));
            assertEquals(1L, head(f).dataVersion());
            assertEquals(
                0,
                sql().queryForObject(
                    "SELECT COUNT(*) FROM " +
                        NativeSqlLayout.identifier(
                            new NativeSqlLayout(f.dataset().schema()).table()
                        ),
                    Integer.class
                )
            );
            assertEquals(
                0,
                sql().queryForObject(
                    "SELECT COUNT(*) FROM " +
                        NativeSqlLayout.identifier(
                            new NativeSqlLayout(f.dataset().schema()).historyTable()
                        ),
                    Integer.class
                )
            );
            assertEquals(
                0,
                sql().queryForObject(
                    "SELECT COUNT(*) FROM cfg_row_receipt WHERE review_id=?",
                    Integer.class,
                    reserved.id()
                )
            );
            assertEquals(
                "APPLYING",
                reviews().inspect(f.account(), f.actor(), reserved.id()).status()
            );
        } finally {
            sql().execute("ALTER TABLE cfg_outbox DROP CONSTRAINT " + constraint);
        }
        assertEquals("SUCCEEDED", apply(f, prepared).status());
    }

    @Test
    void signedSeekPaginationRejectsTamperingAndReportsLiveDrift() {
        var f = fixture(false);
        insert(f, "a");
        insert(f, "b");
        insert(f, "c");
        var first = queries(clock).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 1, null).toString())
        );
        assertEquals("PARTIAL", first.completeness());
        assertNotNull(first.nextCursor());
        var next = query(f, 1, first.nextCursor());
        var second = queries(clock).query(f.account(), f.actor(), parser.parse(next.toString()));
        assertNotEquals(first.rows().getFirst().recordId(), second.rows().getFirst().recordId());
        var changed = query(f, 2, first.nextCursor());
        assertEquals(
            "CURSOR_CONFLICT",
            assertThrows(VclException.class, () ->
                queries(clock).query(f.account(), f.actor(), parser.parse(changed.toString()))
            ).code()
        );
        var tampered = query(f, 1, "x" + first.nextCursor().substring(1));
        assertEquals(
            "CURSOR_INVALID",
            assertThrows(VclException.class, () ->
                queries(clock).query(f.account(), f.actor(), parser.parse(tampered.toString()))
            ).code()
        );
        assertEquals(
            "CURSOR_EXPIRED",
            assertThrows(VclException.class, () ->
                queries(Clock.offset(clock, Duration.ofMinutes(11))).query(
                    f.account(),
                    f.actor(),
                    parser.parse(next.toString())
                )
            ).code()
        );
        insert(f, "d");
        String cursor = second.nextCursor();
        com.voltcanary.device.configurable.domain.NativeQueryPage last;
        do {
            last = queries(clock).query(
                f.account(),
                f.actor(),
                parser.parse(query(f, 1, cursor).toString())
            );
            cursor = last.nextCursor();
        } while (cursor != null);
        assertEquals("UNKNOWN", last.completeness());
        assertEquals("LIVE_DATA_CHANGED", last.truncationReason());
    }

    @Test
    void editsExpiryAndFreshAuthorityCannotBypassReview() {
        var f = fixture(false);
        var reserved = reserve(f);
        var doc = mutation(f, reserved, "INSERT", "first", null);
        var first = prepare(f, reserved, doc);
        ((ObjectNode) doc.at("/spec/key/0/literal")).put("value", "second");
        var second = prepare(f, reserved, doc);
        assertEquals(first.revision() + 1, second.revision());
        assertEquals(
            "REVIEW_CONFLICT",
            assertThrows(VclException.class, () -> apply(f, first)).code()
        );
        assertEquals(
            "ACCOUNT_ACCESS_REVOKED",
            assertThrows(VclException.class, () ->
                reviews().inspect(f.account(), UUID.randomUUID(), reserved.id())
            ).code()
        );
        var later = new JdbcNativeRowReviews(source(), Clock.offset(clock, Duration.ofMinutes(16)));
        assertEquals("EXPIRED", later.inspect(f.account(), f.actor(), reserved.id()).status());
        assertEquals(
            "REVIEW_EXPIRED",
            assertThrows(VclException.class, () ->
                later.begin(
                    f.account(),
                    f.actor(),
                    reserved.id(),
                    second.revision(),
                    second.reviewDigest(),
                    second.reservationKey()
                )
            ).code()
        );
        sql().update("UPDATE accounts SET status='DELETED' WHERE id=?", f.account());
        assertEquals(
            "ACCOUNT_ACCESS_REVOKED",
            assertThrows(VclException.class, () ->
                reviews().inspect(f.account(), f.actor(), reserved.id())
            ).code()
        );
    }

    @Test
    void unsupportedQueriesAndNulNeverBecomeSilentSqlCoercion() {
        var f = fixture(false);
        var reserved = reserve(f);
        var nul = mutation(f, reserved, "INSERT", "nul\0key", null);
        assertEquals(
            "NATIVE_TEXT_UNSUPPORTED",
            assertThrows(VclException.class, () -> prepare(f, reserved, nul)).code()
        );
        var query = query(f, 10, null);
        ((ObjectNode) query.at("/spec/order/0")).put("fieldId", NativeFixtures.TEXT.toString());
        assertEquals(
            "QUERY_ORDER_UNSUPPORTED",
            assertThrows(VclException.class, () ->
                queries(clock).query(f.account(), f.actor(), parser.parse(query.toString()))
            ).code()
        );
        ((ObjectNode) query.at("/spec/order/0")).put(
            "fieldId",
            f.dataset().recordIdFieldId().toString()
        );
        var where = ((ObjectNode) query.path("spec"))
            .putObject("where")
            .put("op", "CALL")
            .put("method", "text_prefix");
        var args = where.putArray("args");
        args.addObject().put("op", "FIELD").put("fieldId", NativeFixtures.TEXT.toString());
        args.addObject()
            .put("op", "CONST")
            .putObject("literal")
            .put("type", "TEXT")
            .put("value", "key");
        assertEquals(
            "QUERY_PUSHDOWN_UNSUPPORTED",
            assertThrows(VclException.class, () ->
                queries(clock).query(f.account(), f.actor(), parser.parse(query.toString()))
            ).code()
        );
        insert(f, "key");
        insert(f, "KEY");
        where.put("method", "eq");
        var matched = queries(clock).query(f.account(), f.actor(), parser.parse(query.toString()));
        assertEquals(
            List.of("key"),
            matched
                .rows()
                .stream()
                .map(row -> row.values().get(NativeFixtures.TEXT).path("value").asText())
                .toList()
        );
    }

    @Test
    void indexedNumericFilterPreservesNullAndExactDecimalSemantics() {
        var f = fixture(false);
        insert(f, "unknown");
        var review = reserve(f);
        var doc = mutation(f, review, "INSERT", "measured", null);
        value(doc, 2, "DECIMAL").put("unit", "V").put("value", "999999999999999.999");
        apply(f, prepare(f, review, doc));
        var request = query(f, 10, null);
        var where = ((ObjectNode) request.path("spec"))
            .putObject("where")
            .put("op", "CALL")
            .put("method", "gt");
        var args = where.putArray("args");
        args.addObject().put("op", "FIELD").put("fieldId", NativeFixtures.AMOUNT.toString());
        args.addObject()
            .put("op", "CONST")
            .putObject("literal")
            .put("type", "DECIMAL")
            .put("unit", "V")
            .put("value", "999999999999999.998");
        var result = queries(clock).query(f.account(), f.actor(), parser.parse(request.toString()));
        assertEquals(1, result.rows().size());
        assertEquals(
            "measured",
            result.rows().getFirst().values().get(NativeFixtures.TEXT).path("value").asText()
        );
        where.put("method", "is_null");
        args.remove(1);
        result = queries(clock).query(f.account(), f.actor(), parser.parse(request.toString()));
        assertEquals(1, result.rows().size());
        assertEquals(
            "unknown",
            result.rows().getFirst().values().get(NativeFixtures.TEXT).path("value").asText()
        );
        ((ObjectNode) args.get(0)).put("fieldId", new UUID(0, 4).toString());
        assertEquals(
            "INDEX_REQUIRED",
            assertThrows(VclException.class, () ->
                queries(clock).query(f.account(), f.actor(), parser.parse(request.toString()))
            ).code()
        );
    }

    @Test
    void currentRowVersionGenerationAndIdentityAreIndependentFences() {
        var f = fixture(false);
        insert(f, "unique");
        var duplicate = reserve(f);
        var duplicatePrepared = prepare(
            f,
            duplicate,
            mutation(f, duplicate, "INSERT", "unique", null)
        );
        assertEquals(
            "ROW_IDENTITY_CONFLICT",
            assertThrows(VclException.class, () -> apply(f, duplicatePrepared)).code()
        );
        var stale = reserve(f);
        var doc = mutation(f, stale, "UPDATE", "unique", "2");
        value(doc, 2, "DECIMAL").put("unit", "V").put("value", "2");
        var wrongVersion = prepare(f, stale, doc);
        assertEquals(
            "ROW_CONFLICT",
            assertThrows(VclException.class, () -> apply(f, wrongVersion)).code()
        );
        var fenced = reserve(f);
        var prepared = prepare(f, fenced, mutation(f, fenced, "INSERT", "new generation", null));
        changeGeneration(f);
        assertEquals(
            "TARGET_CONFLICT",
            assertThrows(VclException.class, () -> apply(f, prepared)).code()
        );
        assertEquals(2L, head(f).dataVersion());
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_row_receipt WHERE account_id=?",
                Integer.class,
                f.account()
            )
        );
    }

    @Test
    void concurrentReapplyOfSameReviewedIntentCommitsExactlyOnce() throws Exception {
        var f = fixture(false);
        var reserved = reserve(f);
        var prepared = prepare(f, reserved, mutation(f, reserved, "INSERT", "one", null));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(
                List.<java.util.concurrent.Callable<NativeRowReview>>of(
                    () -> apply(f, prepared),
                    () -> apply(f, prepared)
                )
            );
            assertEquals(results.get(0).get(), results.get(1).get());
        }
        assertEquals(2L, head(f).dataVersion());
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_row_receipt WHERE account_id=?",
                Integer.class,
                f.account()
            )
        );
        assertEquals(
            1,
            sql().queryForObject(
                "SELECT COUNT(*) FROM cfg_outbox WHERE account_id=?",
                Integer.class,
                f.account()
            )
        );
    }

    @Test
    void nativeReferencesAreAuthorizedAgainAtCommitAndGuardDeletion() {
        var target = fixture(false);
        var targetRow = insert(target, "destination");
        var document = NativeFixtures.document(UUID.randomUUID(), false);
        ((ObjectNode) document.at("/spec/fields/8"))
            .putObject("target")
            .put("kind", "DATASET")
            .put("datasetId", target.dataset().schema().id().toString());
        (
            (com.fasterxml.jackson.databind.node.ArrayNode) document.at(
                "/spec/storage/indexedFieldIds"
            )
        ).add(new UUID(0, 9).toString());
        var schema = com.voltcanary.device.configurable.domain.NativeSchema.compile(
            document.toString(),
            JdbcNativeDatasetCatalog.emptyMetadata(target.account())
        );
        var catalog = new JdbcNativeDatasetCatalog(source());
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
        var doc = mutation(source, reference, "INSERT", "linked", null);
        value(doc, 9, "REF").put("value", targetRow.createdRecordId().toString());
        apply(source, prepare(source, reference, doc));
        var deletion = reserve(target);
        var blocked = prepare(
            target,
            deletion,
            mutation(target, deletion, "DELETE", "destination", "1")
        );
        assertEquals(
            "ROW_REFERENCED",
            assertThrows(VclException.class, () -> apply(target, blocked)).code()
        );
        var unlink = reserve(source);
        var edit = mutation(source, unlink, "UPDATE", "linked", "1");
        value(edit, 9, "NULL").putNull("value");
        apply(source, prepare(source, unlink, edit));
        var pending = reserve(source);
        var pendingDoc = mutation(source, pending, "INSERT", "too late", null);
        value(pendingDoc, 9, "REF").put("value", targetRow.createdRecordId().toString());
        var prepared = prepare(source, pending, pendingDoc);
        apply(target, blocked);
        assertEquals(
            "REFERENCE_TARGET_UNAVAILABLE",
            assertThrows(VclException.class, () -> apply(source, prepared)).code()
        );
    }

    @Test
    void cursorLeaseDoesNotRenewAndScopeOrGenerationChangesInvalidateIt() {
        var f = fixture(false);
        insert(f, "a");
        insert(f, "b");
        insert(f, "c");
        var first = queries(clock).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 1, null).toString())
        );
        var second = queries(Clock.offset(clock, Duration.ofMinutes(9))).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 1, first.nextCursor()).toString())
        );
        assertNotNull(second.nextCursor());
        assertEquals(
            "CURSOR_EXPIRED",
            assertThrows(VclException.class, () ->
                queries(Clock.offset(clock, Duration.ofMinutes(11))).query(
                    f.account(),
                    f.actor(),
                    parser.parse(query(f, 1, second.nextCursor()).toString())
                )
            ).code()
        );
        changeGeneration(f);
        assertEquals(
            "CURSOR_CONFLICT",
            assertThrows(VclException.class, () ->
                queries(clock).query(
                    f.account(),
                    f.actor(),
                    parser.parse(query(f, 1, first.nextCursor()).toString())
                )
            ).code()
        );
        sql().update(
            "UPDATE accounts SET updated_at=? WHERE id=?",
            java.time.LocalDateTime.of(2030, 1, 1, 0, 0),
            f.account()
        );
        assertEquals(
            "CURSOR_SCOPE_CHANGED",
            assertThrows(VclException.class, () ->
                queries(clock).query(
                    f.account(),
                    f.actor(),
                    parser.parse(query(f, 1, first.nextCursor()).toString())
                )
            ).code()
        );
    }

    @Test
    void descendingSeekUsesTheSameTotalOrderWithoutDuplicateRows() {
        var f = fixture(false);
        insert(f, "a");
        insert(f, "b");
        insert(f, "c");
        var ascending = queries(clock).query(
            f.account(),
            f.actor(),
            parser.parse(query(f, 10, null).toString())
        );
        var actual = new java.util.ArrayList<UUID>();
        String cursor = null;
        do {
            var document = query(f, 1, cursor);
            ((ObjectNode) document.at("/spec/order/0")).put("direction", "DESC");
            var page = queries(clock).query(
                f.account(),
                f.actor(),
                parser.parse(document.toString())
            );
            actual.add(page.rows().getFirst().recordId());
            cursor = page.nextCursor();
            if (cursor == null) assertEquals("COMPLETE", page.completeness());
        } while (cursor != null);
        assertEquals(
            ascending
                .rows()
                .reversed()
                .stream()
                .map(com.voltcanary.device.configurable.domain.NativeRow::recordId)
                .toList(),
            actual
        );
    }

    private void changeGeneration(Fixture f) {
        UUID generation = UUID.randomUUID();
        sql().update(
            "UPDATE cfg_native_generation SET generation=? WHERE dataset_id=? AND status='ACTIVE'",
            generation,
            f.dataset().schema().id()
        );
        sql().update(
            "UPDATE cfg_dataset SET generation=? WHERE id=?",
            generation,
            f.dataset().schema().id()
        );
    }

    @Test
    void serverAssignedSystemRecordKeySupportsUpdateAndDelete() {
        var initial = fixture(false);
        var schemaDocument = NativeFixtures.document(UUID.randomUUID(), false);
        ((ObjectNode) schemaDocument.path("spec")).putArray("keyFieldIds");
        var schema = com.voltcanary.device.configurable.domain.NativeSchema.compile(
            schemaDocument.toString(),
            JdbcNativeDatasetCatalog.emptyMetadata(initial.account())
        );
        var catalog = new JdbcNativeDatasetCatalog(source());
        catalog.register(
            initial.account(),
            initial.actor(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            schema
        );
        var f = new Fixture(
            initial.actor(),
            initial.account(),
            catalog.provision(initial.account(), schema.id())
        );
        var insert = reserve(f);
        var doc = mutation(f, insert, "INSERT", "unused", null);
        ((ObjectNode) doc.path("spec")).putArray("key");
        value(doc, 1, "TEXT").put("value", "initial");
        apply(f, prepare(f, insert, doc));
        var update = reserve(f);
        var edit = mutation(f, update, "UPDATE", "unused", "1");
        ((ObjectNode) edit.at("/spec/key/0"))
            .put("fieldId", f.dataset().recordIdFieldId().toString())
            .putObject("literal")
            .put("type", "REF")
            .put("value", insert.createdRecordId().toString());
        value(edit, 1, "TEXT").put("value", "edited");
        apply(f, prepare(f, update, edit));
        var row = queries(clock)
            .query(f.account(), f.actor(), parser.parse(query(f, 10, null).toString()))
            .rows()
            .getFirst();
        assertEquals("edited", row.values().get(NativeFixtures.TEXT).path("value").asText());
        assertEquals("2", row.recordVersion());
        var deletion = reserve(f);
        var remove = mutation(f, deletion, "DELETE", "unused", "2");
        ((ObjectNode) remove.at("/spec/key/0"))
            .put("fieldId", f.dataset().recordIdFieldId().toString())
            .putObject("literal")
            .put("type", "REF")
            .put("value", insert.createdRecordId().toString());
        apply(f, prepare(f, deletion, remove));
        assertTrue(
            queries(clock)
                .query(f.account(), f.actor(), parser.parse(query(f, 10, null).toString()))
                .rows()
                .isEmpty()
        );
    }
}
