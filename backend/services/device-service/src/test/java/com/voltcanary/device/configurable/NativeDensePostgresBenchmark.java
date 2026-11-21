package com.voltcanary.device.configurable;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.device.configurable.adapter.JdbcNativeDatasetCatalog;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowMutations;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowQueries;
import com.voltcanary.device.configurable.adapter.JdbcNativeRowReviews;
import com.voltcanary.device.configurable.adapter.NativeQueryCursor;
import com.voltcanary.device.configurable.adapter.NativeSqlLayout;
import com.voltcanary.device.configurable.domain.NativeQueryPage;
import com.voltcanary.device.configurable.domain.NativeRow;
import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.device.configurable.domain.NativeSchema;
import com.voltcanary.vcl.VclCanonical;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run explicitly with -Dtest=NativeDensePostgresBenchmark#pairedOwnerReads+pairedReviewedInserts. */
class NativeDensePostgresBenchmark extends NativeRowsPostgresTest {
    private static final int ROWS = 20_000;
    private static final int WARMUP = 100;
    private static final int SAMPLES = 500;
    private static final String KEY = "row-001000";
    private static final java.lang.management.ThreadMXBean THREAD_CLOCK = ManagementFactory.getThreadMXBean();

    @Test void pairedOwnerReads() throws SQLException, com.fasterxml.jackson.core.JsonProcessingException {
        var fixture = scalarFixture();
        var layout = new NativeSqlLayout(fixture.dataset());
        String table = NativeSqlLayout.identifier(layout.table());
        String name = NativeSqlLayout.identifier(NativeSqlLayout.column(NativeFixtures.TEXT));
        seed(fixture);
        var cursors = new NativeQueryCursor(new byte[32], clock);
        try (var pool = pool()) {
            var generic = new JdbcNativeRowQueries(pool, cursors);
            var results = NativeFixtures.MAPPER.createObjectNode().put("rows", ROWS).put("samplesPerOperation", SAMPLES)
                    .put("warmupsPerOperation", WARMUP).put("concurrency", 1).put("history", false)
                    .put("postgres", sql().queryForObject("SHOW server_version", String.class))
                    .put("sharedBuffers", sql().queryForObject("SHOW shared_buffers", String.class))
                    .put("javaLogicalProcessors", Runtime.getRuntime().availableProcessors());
            for (var workload : new String[]{"list", "get"}) {
                boolean lookup = workload.equals("get");
                int limit = lookup ? 1 : 20;
                var request = query(fixture, limit, null);
                var spec = (ObjectNode) request.path("spec");
                spec.putArray("select").add(NativeFixtures.TEXT.toString());
                if (lookup) {
                    var operands = spec.putObject("where").put("op", "CALL").put("method", "eq").putArray("args");
                    operands.addObject().put("op", "FIELD").put("fieldId", NativeFixtures.TEXT.toString());
                    operands.addObject().put("op", "CONST").putObject("literal").put("type", "TEXT").put("value", KEY);
                }
                var document = parser.parse(request.toString());
                String digest = VclCanonical.documentDigest(document);
                String select = "SELECT record_id,record_version," + name + " FROM " + table
                        + (lookup ? " WHERE " + name + "=?" : "") + " ORDER BY record_id ASC LIMIT ?";
                assertEquals(generic.query(fixture.account(), fixture.actor(), document),
                        baseline(pool, fixture, cursors, select, lookup, limit, digest));
                var genericTimes = new long[SAMPLES];
                var typedTimes = new long[SAMPLES];
                long genericCpu = 0;
                long typedCpu = 0;
                for (int index = -WARMUP; index < SAMPLES; index++) {
                    Timing genericTime;
                    Timing typedTime;
                    if (index % 2 == 0) {
                        genericTime = measure(() -> generic.query(fixture.account(), fixture.actor(), document));
                        typedTime = measure(() -> baseline(pool, fixture, cursors, select, lookup, limit, digest));
                    } else {
                        typedTime = measure(() -> baseline(pool, fixture, cursors, select, lookup, limit, digest));
                        genericTime = measure(() -> generic.query(fixture.account(), fixture.actor(), document));
                    }
                    if (index >= 0) {
                        genericTimes[index] = genericTime.wallNanos();
                        typedTimes[index] = typedTime.wallNanos();
                        genericCpu += genericTime.cpuNanos();
                        typedCpu += typedTime.cpuNanos();
                    }
                }
                var summary = results.putObject(workload);
                summary.set("genericMs", percentiles(genericTimes));
                summary.set("typedMs", percentiles(typedTimes));
                summary.set("meanClientThreadCpuMs", cpuMeans(genericCpu, typedCpu));
                summary.put("p95OverheadPercent", (percentile(genericTimes, 95) / percentile(typedTimes, 95) - 1) * 100);
                String plan = lookup ? sql().queryForObject("EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON) " + select,
                        String.class, KEY, limit + 1) : sql().queryForObject("EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON) " + select,
                        String.class, limit + 1);
                summary.set("physicalPlan", NativeFixtures.MAPPER.readTree(plan));
            }
            results.put("heapBytes", sql().queryForObject("SELECT pg_relation_size(?::regclass)", Long.class, layout.table()));
            results.put("indexBytes", sql().queryForObject("SELECT pg_indexes_size(?::regclass)", Long.class, layout.table()));
            System.out.println("NATIVE_DENSE_OWNER_READS " + results);
        }
    }

    @Test void pairedReviewedInserts() throws SQLException {
        var genericFixture = scalarFixture();
        var typedFixture = scalarFixture();
        seed(genericFixture);
        seed(typedFixture);
        var genericTimes = new long[SAMPLES];
        var typedTimes = new long[SAMPLES];
        long genericCpu = 0;
        long typedCpu = 0;
        String startWal = sql().queryForObject("SELECT pg_current_wal_insert_lsn()::text", String.class);
        try (var pool = pool()) {
            var generic = new JdbcNativeRowMutations(pool, clock);
            var reviews = new JdbcNativeRowReviews(pool, clock);
            var sql = new JdbcTemplate(pool);
            for (int index = -WARMUP; index < SAMPLES; index++) {
                String key = "new-row-" + index;
                var genericReview = startInsert(reviews, genericFixture, key);
                var typedReview = startInsert(reviews, typedFixture, key);
                Timing genericTime;
                Timing typedTime;
                if (index % 2 == 0) {
                    genericTime = measure(() -> generic.apply(genericFixture.account(), genericFixture.actor(), genericReview.id()));
                    typedTime = measure(() -> typedInsert(pool, typedFixture, typedReview, key));
                } else {
                    typedTime = measure(() -> typedInsert(pool, typedFixture, typedReview, key));
                    genericTime = measure(() -> generic.apply(genericFixture.account(), genericFixture.actor(), genericReview.id()));
                }
                verifyInsert(sql, genericFixture, genericReview, key, index + WARMUP + 2);
                verifyInsert(sql, typedFixture, typedReview, key, index + WARMUP + 2);
                if (index >= 0) {
                    genericTimes[index] = genericTime.wallNanos();
                    typedTimes[index] = typedTime.wallNanos();
                    genericCpu += genericTime.cpuNanos();
                    typedCpu += typedTime.cpuNanos();
                }
            }
        }
        var result = NativeFixtures.MAPPER.createObjectNode().put("rowsAtStart", ROWS).put("samplesPerOperation", SAMPLES)
                .put("warmupsPerOperation", WARMUP).put("concurrency", 1).put("history", false)
                .put("scope", "reviewed insert commit");
        result.set("genericMs", percentiles(genericTimes));
        result.set("typedMs", percentiles(typedTimes));
        result.set("meanClientThreadCpuMs", cpuMeans(genericCpu, typedCpu));
        result.put("pairedRunWalBytes", sql().queryForObject(
                "SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(),?::pg_lsn)::bigint", Long.class, startWal));
        result.put("walScope", "Both variants including warm-up, preparation, verification and database background activity");
        result.put("p95OverheadPercent", (percentile(genericTimes, 95) / percentile(typedTimes, 95) - 1) * 100);
        System.out.println("NATIVE_DENSE_OWNER_INSERT " + result);
    }

    private static HikariDataSource pool() {
        var settings = new HikariConfig();
        settings.setJdbcUrl(POSTGRES.getJdbcUrl());
        settings.setUsername(POSTGRES.getUsername());
        settings.setPassword(POSTGRES.getPassword());
        settings.setMaximumPoolSize(1);
        return new HikariDataSource(settings);
    }

    private void seed(Fixture fixture) {
        String table = NativeSqlLayout.identifier(new NativeSqlLayout(fixture.dataset()).table());
        String name = NativeSqlLayout.identifier(NativeSqlLayout.column(NativeFixtures.TEXT));
        sql().update("INSERT INTO " + table + " (record_id,record_version,external_key," + name + ") "
                + "SELECT md5(i::text)::uuid,1,md5(i::text)||md5('key-'||i::text),'row-' || lpad(i::text,6,'0') "
                + "FROM generate_series(1,?) AS i", ROWS);
        sql().execute("ANALYZE " + table);
    }

    private NativeRowReview startInsert(JdbcNativeRowReviews reviews, Fixture fixture, String key) {
        var account = fixture.account();
        var actor = fixture.actor();
        var reserved = reviews.reserve(account, actor, fixture.dataset().schema().id(), 1, UUID.randomUUID().toString());
        var prepared = reviews.prepare(account, actor, reserved.id(), parser.parse(mutation(fixture, reserved, "INSERT", key, null).toString()));
        return reviews.begin(account, actor, prepared.id(), prepared.revision(), prepared.reviewDigest(), prepared.reservationKey());
    }

    private void typedInsert(HikariDataSource source, Fixture fixture, NativeRowReview review, String key) throws SQLException {
        var dataset = fixture.dataset();
        var document = parser.parse(review.canonicalDocument());
        long dataVersion = document.spec().at("/targetPrecondition/dataVersion").longValue();
        String table = NativeSqlLayout.identifier(new NativeSqlLayout(dataset).table());
        String name = NativeSqlLayout.identifier(NativeSqlLayout.column(NativeFixtures.TEXT));
        var tuple = NativeFixtures.MAPPER.createArrayNode();
        tuple.addObject().put("fieldId", NativeFixtures.TEXT.toString()).put("type", "TEXT").put("value", key);
        String hash = VclCanonical.sha256(VclCanonical.encode(tuple));
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            setTimeouts(connection);
            assertEquals(review.scopeDigest(), ownerScope(connection, fixture));
            try (var query = connection.prepareStatement("""
                    SELECT canonical_document,document_digest,expires_at FROM cfg_row_review
                    WHERE id=? AND account_id=? AND actor_id=? AND dataset_id=? AND status='APPLYING' FOR UPDATE
                    """)) {
                query.setObject(1, review.id());
                query.setObject(2, fixture.account());
                query.setObject(3, fixture.actor());
                query.setObject(4, dataset.schema().id());
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(review.canonicalDocument(), rows.getString(1));
                    assertEquals(rows.getString(2), VclCanonical.sha256(rows.getString(1)));
                    assertTrue(clock.instant().isBefore(rows.getObject(3, java.time.OffsetDateTime.class).toInstant()));
                }
            }
            execute(connection, """
                    UPDATE cfg_dataset SET data_version=data_version+1,updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND account_id=? AND generation=? AND data_version=? AND schema_revision=1 AND status='READY'
                    """, dataset.schema().id(), fixture.account(), dataset.generation(), dataVersion);
            execute(connection, "INSERT INTO " + table + "(record_id,record_version,external_key," + name + ") VALUES(?,1,?,?)",
                    review.createdRecordId(), hash, key);
            String payload = VclCanonical.encode(NativeFixtures.MAPPER.createObjectNode().put("mutationId", review.mutationId().toString())
                    .put("datasetId", review.datasetId().toString()).put("recordId", review.createdRecordId().toString())
                    .put("recordVersion", "1").put("dataVersion", dataVersion + 1).put("generation", dataset.generation().toString())
                    .put("schemaRevision", 1).put("deleted", false));
            execute(connection, "INSERT INTO cfg_row_receipt(id,account_id,review_id,operation,idempotency_key,payload_digest,result_json) VALUES(?,?,?,'INSERT',?,?,?)",
                    review.receiptId(), fixture.account(), review.id(), review.reservationKey(), review.documentDigest(), payload);
            execute(connection, "INSERT INTO cfg_outbox(id,account_id,aggregate_id,event_type,payload_json) VALUES(?,?,?,'NATIVE_ROW_MUTATED',?)",
                    review.receiptId(), fixture.account(), review.datasetId(), payload);
            execute(connection, "UPDATE cfg_row_review SET status='SUCCEEDED',outcome_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", review.id());
            connection.commit();
        }
    }

    private static void verifyInsert(JdbcTemplate sql, Fixture fixture, NativeRowReview review, String key, long version) {
        String table = NativeSqlLayout.identifier(new NativeSqlLayout(fixture.dataset()).table());
        String name = NativeSqlLayout.identifier(NativeSqlLayout.column(NativeFixtures.TEXT));
        assertEquals(key, sql.queryForObject("SELECT " + name + " FROM " + table + " WHERE record_id=?", String.class, review.createdRecordId()));
        assertEquals("SUCCEEDED", sql.queryForObject("SELECT status FROM cfg_row_review WHERE id=?", String.class, review.id()));
        assertEquals(version, sql.queryForObject("SELECT data_version FROM cfg_dataset WHERE id=?", Long.class, review.datasetId()));
        assertEquals(sql.queryForObject("SELECT result_json FROM cfg_row_receipt WHERE id=?", String.class, review.receiptId()),
                sql.queryForObject("SELECT payload_json FROM cfg_outbox WHERE id=?", String.class, review.receiptId()));
    }

    private Fixture scalarFixture() {
        var account = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var id = UUID.randomUUID();
        sql().update("INSERT INTO accounts(id,user_id,status) VALUES(?,?,'ACTIVE')", account, actor);
        var document = NativeFixtures.document(id, false);
        var fields = (ArrayNode) document.at("/spec/fields");
        var keyField = fields.get(0);
        var amountField = fields.get(1);
        fields.removeAll().add(keyField).add(amountField);
        var schema = NativeSchema.compile(document.toString(), JdbcNativeDatasetCatalog.emptyMetadata(account));
        var catalog = new JdbcNativeDatasetCatalog(source());
        catalog.register(account, actor, UUID.randomUUID(), UUID.randomUUID(), schema);
        return new Fixture(actor, account, catalog.provision(account, id));
    }

    private NativeQueryPage baseline(HikariDataSource source, Fixture fixture, NativeQueryCursor cursors,
            String select, boolean lookup, int limit, String digest) throws SQLException {
        var dataset = fixture.dataset();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            setTimeouts(connection);
            String scope = ownerScope(connection, fixture);
            try (var query = connection.prepareStatement("""
                    SELECT d.generation,d.data_version FROM cfg_dataset d
                    JOIN cfg_native_generation g ON g.account_id=d.account_id AND g.dataset_id=d.id AND g.generation=d.generation
                    WHERE d.id=? AND d.account_id=? AND d.status='READY' AND d.schema_revision=1
                      AND d.physical_id=g.physical_id AND g.status='ACTIVE' FOR UPDATE OF d
                    """)) {
                query.setObject(1, dataset.schema().id());
                query.setObject(2, fixture.account());
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(dataset.generation(), rows.getObject(1, UUID.class));
                    assertEquals(dataset.dataVersion(), rows.getLong(2));
                }
            }
            var rows = new ArrayList<NativeRow>();
            try (var query = connection.prepareStatement(select)) {
                if (lookup) query.setString(1, KEY);
                query.setInt(lookup ? 2 : 1, limit + 1);
                try (var selected = query.executeQuery()) {
                    while (selected.next()) {
                        var literal = NativeFixtures.MAPPER.createObjectNode().put("type", "TEXT").put("value", selected.getString(3));
                        rows.add(new NativeRow(selected.getObject(1, UUID.class), selected.getString(2), Map.of(NativeFixtures.TEXT, literal)));
                    }
                }
            }
            boolean more = rows.size() > limit;
            if (more) rows.removeLast();
            String cursor = more ? cursors.encode(fixture.actor(), dataset, scope, digest, new NativeQueryCursor.Position(
                    rows.getLast().recordId(), dataset.dataVersion(), false, cursors.first(dataset).expiresAt())) : null;
            var manifest = new NativeQueryPage.ReadManifest(dataset.schema().id(), dataset.schema().revision(), dataset.generation(),
                    dataset.dataVersion(), dataset.schema().digest(), scope, digest, "LIVE", null);
            connection.commit();
            return new NativeQueryPage(rows, cursor, manifest, more ? "PARTIAL" : "COMPLETE", more ? "PAGE_LIMIT" : null);
        }
    }

    private static void setTimeouts(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout='2s'");
            statement.execute("SET LOCAL statement_timeout='10s'");
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String ownerScope(Connection connection, Fixture fixture) throws SQLException {
        try (var query = connection.prepareStatement("SELECT updated_at FROM accounts WHERE id=? AND user_id=? AND status='ACTIVE' FOR UPDATE")) {
            query.setObject(1, fixture.account());
            query.setObject(2, fixture.actor());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return VclCanonical.sha256("NATIVE_OWNER/1:" + fixture.account() + ":" + fixture.actor() + ":" + rows.getString(1));
            }
        }
    }

    private static ObjectNode percentiles(long[] times) {
        Arrays.sort(times);
        return NativeFixtures.MAPPER.createObjectNode().put("p50", percentile(times, 50))
                .put("p95", percentile(times, 95)).put("p99", percentile(times, 99));
    }

    private static double percentile(long[] sorted, int rank) {
        return sorted[(int) Math.ceil(sorted.length * rank / 100.0) - 1] / 1_000_000.0;
    }

    @FunctionalInterface private interface Operation { void run() throws SQLException; }

    private record Timing(long wallNanos, long cpuNanos) { }

    private static Timing measure(Operation operation) throws SQLException {
        long cpuStart = THREAD_CLOCK.isCurrentThreadCpuTimeSupported() ? THREAD_CLOCK.getCurrentThreadCpuTime() : -1;
        long start = System.nanoTime();
        operation.run();
        long elapsed = System.nanoTime() - start;
        long cpuUsed = cpuStart < 0 ? -1 : THREAD_CLOCK.getCurrentThreadCpuTime() - cpuStart;
        return new Timing(elapsed, cpuUsed);
    }

    private static ObjectNode cpuMeans(long genericCpu, long typedCpu) {
        var metrics = NativeFixtures.MAPPER.createObjectNode();
        if (genericCpu >= 0 && typedCpu >= 0) {
            metrics.put("generic", genericCpu / (SAMPLES * 1_000_000.0));
            metrics.put("typed", typedCpu / (SAMPLES * 1_000_000.0));
        }
        return metrics;
    }
}
