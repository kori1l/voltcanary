package com.voltcanary.analytics.vcl;

import com.voltcanary.analytics.vcl.adapter.JdbcCompilationBundleStore;
import com.voltcanary.vcl.VclException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

abstract class BundleStoreContract {
    abstract DataSource dataSource();

    @Test
    void durableRoundTripAndSamePayloadReplayPreserveOriginalRow() {
        var account = UUID.randomUUID();
        var id = UUID.randomUUID();
        var bundle = BundleFixtures.bundle(account, id, "sealed");
        var store = new JdbcCompilationBundleStore(dataSource());
        store.put(bundle);
        var jdbc = new JdbcTemplate(dataSource());
        var created = jdbc.queryForObject("SELECT created_at FROM vcl_compilation_bundles WHERE account_id=? AND bundle_id=?", Object.class, account, id);
        var restarted = new JdbcCompilationBundleStore(dataSource());
        assertEquals(bundle.payload(), restarted.find(account, id).orElseThrow().payload());
        assertEquals(bundle.digest(), restarted.put(BundleFixtures.bundle(account, id, "sealed")).digest());
        assertEquals(created, jdbc.queryForObject("SELECT created_at FROM vcl_compilation_bundles WHERE account_id=? AND bundle_id=?", Object.class, account, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vcl_compilation_bundles WHERE account_id=? AND bundle_id=?", Integer.class, account, id));
    }

    @Test
    void tenantScopeDoesNotLeakOrOverwriteAnExistingIdentity() {
        var account = UUID.randomUUID();
        var other = UUID.randomUUID();
        var id = UUID.randomUUID();
        var store = new JdbcCompilationBundleStore(dataSource());
        var original = BundleFixtures.bundle(account, id, "original");
        store.put(original);
        assertTrue(store.find(other, id).isEmpty());
        store.put(BundleFixtures.bundle(other, id, "other-private-data"));
        assertEquals(original.digest(), store.find(account, id).orElseThrow().digest());
        assertEquals("BUNDLE_ID_REUSED", assertThrows(VclException.class, () -> store.put(BundleFixtures.bundle(account, id, "changed"))).code());
        assertEquals(original.payload(), store.find(account, id).orElseThrow().payload());
    }

    @Test
    void concurrentIdenticalInsertsReturnOneImmutableBundle() throws Exception {
        var bundle = BundleFixtures.bundle(UUID.randomUUID(), UUID.randomUUID(), "same");
        var store = new JdbcCompilationBundleStore(dataSource());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                start.await();
                return store.put(bundle).digest();
            })).toList();
            start.countDown();
            for (var call : calls) assertEquals(bundle.digest(), call.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, new JdbcTemplate(dataSource()).queryForObject(
                "SELECT COUNT(*) FROM vcl_compilation_bundles WHERE account_id=? AND bundle_id=?", Integer.class, bundle.accountId(), bundle.id()));
    }

    @Test
    void concurrentConflictingInsertHasExactlyOneWinner() throws Exception {
        var account = UUID.randomUUID();
        var id = UUID.randomUUID();
        var a = BundleFixtures.bundle(account, id, "candidate-a");
        var b = BundleFixtures.bundle(account, id, "candidate-b");
        var store = new JdbcCompilationBundleStore(dataSource());
        var start = new CountDownLatch(1);
        var outcomes = new java.util.ArrayList<String>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = List.of(a, b).stream().map(bundle -> pool.submit(() -> {
                start.await();
                try { return store.put(bundle).digest(); }
                catch (VclException error) { return error.code(); }
            })).toList();
            start.countDown();
            for (var call : calls) outcomes.add(call.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1L, outcomes.stream().filter(value -> value.equals("BUNDLE_ID_REUSED")).count());
        var winner = store.find(account, id).orElseThrow();
        assertTrue(outcomes.contains(winner.digest()));
    }

    @Test
    void corruptedBytesAndUnavailableVersionsFailClosedRatherThanBeingRepaired() {
        var bundle = BundleFixtures.bundle(UUID.randomUUID(), UUID.randomUUID(), "private-data");
        var store = new JdbcCompilationBundleStore(dataSource());
        store.put(bundle);
        var jdbc = new JdbcTemplate(dataSource());
        jdbc.update("UPDATE vcl_compilation_bundles SET canonical_payload=? WHERE account_id=? AND bundle_id=?", bundle.payload() + " ", bundle.accountId(), bundle.id());
        assertEquals("BUNDLE_INTEGRITY", assertThrows(VclException.class, () -> store.find(bundle.accountId(), bundle.id())).code());
        assertEquals("BUNDLE_INTEGRITY", assertThrows(VclException.class, () -> store.put(bundle)).code());
        jdbc.update("UPDATE vcl_compilation_bundles SET bundle_version='unavailable' WHERE account_id=? AND bundle_id=?", bundle.accountId(), bundle.id());
        assertEquals("BUNDLE_VERSION_UNAVAILABLE", assertThrows(VclException.class, () -> store.find(bundle.accountId(), bundle.id())).code());
    }
}
