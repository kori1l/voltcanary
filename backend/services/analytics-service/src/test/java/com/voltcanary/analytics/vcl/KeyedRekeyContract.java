package com.voltcanary.analytics.vcl;

import com.voltcanary.analytics.vcl.KeyedStateStore.Acknowledgement;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.Lease;
import com.voltcanary.analytics.vcl.KeyedStateStore.Output;
import com.voltcanary.analytics.vcl.KeyedStateStore.OutputKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.analytics.vcl.KeyedStateStore.Timer;
import com.voltcanary.analytics.vcl.KeyedStateStore.TimerKind;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore;
import com.voltcanary.vcl.VclException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class KeyedRekeyContract extends KeyedOutputContract {
    private static final String PINS = "a".repeat(64);
    private static final State INITIAL = State.parse("{}");
    private KeyedStateStore store() { return new JdbcKeyedStateStore(source()); }
    private JdbcTemplate jdbc() { return new JdbcTemplate(source()); }
    private record Setup(Key origin, Lease originLease, Key target, Lease targetLease, Output output, Transition transition) { }

    @Test void rekeyCommitsTargetStateTimersOutputsReceiptAndSourceAckTogether() {
        var setup = setup();
        var receipt = consume(store(), setup);
        assertFalse(receipt.target().replay());
        assertEquals(1, receipt.target().committedRevision());
        assertEquals(setup.transition().next(), store().inspect(setup.target(), PINS).state());
        assertEquals(1, store().inspect(setup.origin(), PINS).revision());
        var output = store().inspectOutput(setup.origin(), PINS, setup.output().id());
        assertEquals(receipt.id(), output.receiptId());
        assertEquals(receipt.digest(), output.receiptDigest());
        assertTrue(store().pendingOutputs(setup.origin(), PINS, 128).isEmpty());
        assertEquals(setup.transition().outputs().getFirst(), store().pendingOutputs(setup.target(), PINS, 128).getFirst().output());
        assertEquals(1, store().dueTimers(setup.target().accountId(), 128).size());
        assertEquals(1, counter(setup.target()));
        assertEquals(1, receipts(setup.origin()));
    }

    @Test void lostCommitAckReplaysTheVerifiedLocalReceiptEvenAfterLeaseExpiry() {
        var setup = setup();
        code("KEY_STORAGE_UNAVAILABLE", () -> consume(losingCommitAck(), setup));
        var original = store().inspectOutput(setup.origin(), PINS, setup.output().id());
        expire(setup.origin());
        expire(setup.target());
        var replay = consume(store(), setup);
        assertTrue(replay.target().replay());
        assertEquals(original.receiptId(), replay.id());
        assertEquals(original.receiptDigest(), replay.digest());
        assertEquals(1, store().inspect(setup.target(), PINS).revision());
        assertEquals(1, receipts(setup.origin()));
        assertEquals(1, counter(setup.target()));
    }

    @Test void conflictingReplayCannotReplaceTargetTransitionOrReceipt() {
        var setup = setup();
        var receipt = consume(store(), setup);
        var original = setup.transition();
        var changed = new Transition(original.inputId(), original.inputRevision(), original.inputDigest(), original.expectedRevision(),
                State.parse("{\"changed\":true}"), original.timers(), null, original.outputs());
        code("KEY_INPUT_CONFLICT", () -> store().consumeRekey(setup.origin(), setup.originLease(), setup.target(), setup.targetLease(), changed));
        assertEquals(receipt.id(), consume(store(), setup).id());
        assertEquals(original.next(), store().inspect(setup.target(), PINS).state());
    }

    @Test void identicalConcurrentRekeysCommitOnceAndReplayOnce() throws Exception {
        var setup = setup();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return consume(store(), setup); });
            var b = pool.submit(() -> { start.await(); return consume(store(), setup); });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertNotEquals(first.target().replay(), second.target().replay());
            assertEquals(first.id(), second.id());
            assertEquals(first.digest(), second.digest());
            assertEquals(1, store().inspect(setup.target(), PINS).revision());
        }
    }

    @Test void oppositeDirectionTransfersUseOneLockOrderAndBothComplete() throws Exception {
        var setup = setup();
        var reverse = new Output(UUID.randomUUID(), OutputKind.REKEY, setup.origin(), INITIAL);
        store().commit(setup.target(), setup.targetLease(), new Transition(UUID.randomUUID(), 1, PINS, 0, INITIAL, List.of(), null, List.of(reverse)));
        var forwardTransition = new Transition(setup.output().id(), 1, setup.output().rekeyInputDigest(setup.origin()), 1, INITIAL);
        var reverseTransition = new Transition(reverse.id(), 1, reverse.rekeyInputDigest(setup.target()), 1, INITIAL);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return store().consumeRekey(setup.origin(), setup.originLease(), setup.target(), setup.targetLease(), forwardTransition);
            });
            var b = pool.submit(() -> {
                start.await();
                return store().consumeRekey(setup.target(), setup.targetLease(), setup.origin(), setup.originLease(), reverseTransition);
            });
            start.countDown();
            assertEquals(2, a.get(10, TimeUnit.SECONDS).target().committedRevision());
            assertEquals(2, b.get(10, TimeUnit.SECONDS).target().committedRevision());
            assertTrue(store().pendingOutputs(setup.origin(), PINS, 128).isEmpty());
            assertTrue(store().pendingOutputs(setup.target(), PINS, 128).isEmpty());
        }
    }

    @Test void foreignAccountRuleOrEpochIsRejectedWithoutTargetRegistration() {
        var setup = setup();
        for (var foreign : List.of(
                new Key(UUID.randomUUID(), setup.target().ruleId(), setup.target().deploymentEpoch(), setup.target().subjectDigest(), setup.target().occurrenceDigest()),
                new Key(setup.target().accountId(), UUID.randomUUID(), setup.target().deploymentEpoch(), setup.target().subjectDigest(), setup.target().occurrenceDigest()),
                new Key(setup.target().accountId(), setup.target().ruleId(), UUID.randomUUID(), setup.target().subjectDigest(), setup.target().occurrenceDigest()))) {
            code("KEY_OUTPUT_TARGET_CHANGED", () -> store().consumeRekey(setup.origin(), setup.originLease(), foreign, setup.targetLease(), setup.transition()));
        }
        assertUnchanged(setup);
    }

    @Test void measureOutputCannotBeConsumedAsRekey() {
        var setup = setup();
        var measure = new Output(UUID.randomUUID(), OutputKind.MEASURE, null, INITIAL);
        store().commit(setup.origin(), setup.originLease(), new Transition(UUID.randomUUID(), 1, PINS, 1, INITIAL, List.of(), null, List.of(measure)));
        var transition = new Transition(measure.id(), 2, PINS, 0, INITIAL);
        code("KEY_OUTPUT_TARGET_CHANGED", () -> store().consumeRekey(setup.origin(), setup.originLease(), setup.target(), setup.targetLease(), transition));
        assertUnchanged(setup);
    }

    @Test void invalidSourceOrTargetLeaseCannotConsumeOrAcknowledgeAnything() {
        var setup = setup();
        store().acquire(setup.target(), PINS, setup.targetLease().holderId());
        code("KEY_LEASE_STALE", () -> consume(store(), setup));
        assertUnchanged(setup);
        var expired = setup();
        expire(expired.origin());
        code("KEY_LEASE_STALE", () -> consume(store(), expired));
        assertUnchanged(expired);
    }

    @Test void inputMustBindExactStoredOutputKindSourceRevisionDigestAndTarget() {
        var setup = setup();
        var transition = setup.transition();
        for (var altered : List.of(
                new Transition(transition.inputId(), 2, transition.inputDigest(), 0, INITIAL),
                new Transition(transition.inputId(), 1, setup.output().digest(), 0, INITIAL),
                new Transition(transition.inputId(), 1, "b".repeat(64), 0, INITIAL))) {
            code("KEY_REKEY_INPUT_CHANGED", () -> store().consumeRekey(setup.origin(), setup.originLease(), setup.target(), setup.targetLease(), altered));
        }
        var wrongTarget = new Key(setup.origin().accountId(), setup.origin().ruleId(), setup.origin().deploymentEpoch(), "1".repeat(64), "2".repeat(64));
        store().register(wrongTarget, PINS, INITIAL);
        var lease = store().acquire(wrongTarget, PINS, UUID.randomUUID());
        code("KEY_OUTPUT_TARGET_CHANGED", () -> store().consumeRekey(setup.origin(), setup.originLease(), wrongTarget, lease, transition));
        var timerInput = new Transition(transition.inputId(), 1, transition.inputDigest(), 0, INITIAL, List.of(),
                new KeyedStateStore.TimerInput(transition.inputId(), 1));
        code("KEY_REKEY_INPUT_CHANGED", () -> store().consumeRekey(setup.origin(), setup.originLease(), setup.target(), setup.targetLease(), timerInput));
        assertUnchanged(setup);
    }

    @Test void sameUuidFromDifferentSourcesAndObservationNamespaceRemainDistinct() {
        var first = setup();
        consume(store(), first);
        var origin = new Key(first.origin().accountId(), first.origin().ruleId(), first.origin().deploymentEpoch(), "9".repeat(64), "8".repeat(64));
        var output = new Output(first.output().id(), OutputKind.REKEY, first.target(), first.output().payload());
        store().register(origin, PINS, INITIAL);
        var lease = store().acquire(origin, PINS, UUID.randomUUID());
        store().commit(origin, lease, new Transition(UUID.randomUUID(), 1, PINS, 0, INITIAL, List.of(), null, List.of(output)));
        var next = new Transition(output.id(), 1, output.rekeyInputDigest(origin), 1, State.parse("{\"step\":\"two\"}"));
        store().consumeRekey(origin, lease, first.target(), first.targetLease(), next);
        store().commit(first.target(), first.targetLease(), new Transition(output.id(), 1, PINS, 2, INITIAL));
        assertEquals(3, store().inspect(first.target(), PINS).revision());
        assertEquals(3, counter(first.target()));
        assertNotEquals(first.output().rekeyInputDigest(first.origin()), output.rekeyInputDigest(origin));
    }

    @Test void failedReceiptOrAckSqlRollsBackTargetAndEverySideRecord() {
        for (String table : List.of("vcl_keyed_rekey_receipts", "vcl_keyed_outbox")) {
            var setup = setup();
            String column = table.equals("vcl_keyed_outbox") ? "ack_receipt_id IS NULL OR " : "";
            jdbc().execute("ALTER TABLE " + table + " ADD CONSTRAINT vcl_rekey_failure CHECK (" + column
                    + "output_id <> CAST('" + setup.output().id() + "' AS UUID))");
            try {
                code("KEY_STORAGE_UNAVAILABLE", () -> consume(store(), setup));
                assertUnchanged(setup);
                assertTrue(store().pendingOutputs(setup.target(), PINS, 128).isEmpty());
                assertTrue(store().dueTimers(setup.target().accountId(), 128).isEmpty());
            } finally { jdbc().execute("ALTER TABLE " + table + " DROP CONSTRAINT vcl_rekey_failure"); }
            assertEquals(1, consume(store(), setup).target().committedRevision());
        }
    }

    @Test void targetInboxBudgetAndPinsAreCheckedBeforeAnyEffect() {
        var setup = setup();
        jdbc().update("UPDATE vcl_keyed_state SET inbox_count=4096 WHERE account_id=? AND key_digest=?", setup.target().accountId(), setup.target().digest());
        code("KEY_INBOX_LIMIT", () -> consume(store(), setup));
        assertEquals(0, store().inspect(setup.target(), PINS).revision());
        assertEquals(0, receipts(setup.origin()));
        assertEquals(null, store().inspectOutput(setup.origin(), PINS, setup.output().id()).receiptId());
        jdbc().update("UPDATE vcl_keyed_state SET inbox_count=0,pins_digest=? WHERE account_id=? AND key_digest=?", "b".repeat(64), setup.target().accountId(), setup.target().digest());
        code("KEY_PINS_CHANGED", () -> consume(store(), setup));
        assertEquals(0, receipts(setup.origin()));
    }

    @Test void ordinaryExternalAckCannotPretendToBeALocalRekeyReceipt() {
        var setup = setup();
        store().acknowledge(setup.origin(), setup.originLease(), List.of(new Acknowledgement(setup.output().id(), setup.output().digest(), UUID.randomUUID(), PINS)));
        code("KEY_OUTPUT_ACK_CONFLICT", () -> consume(store(), setup));
        assertEquals(0, store().inspect(setup.target(), PINS).revision());
        assertEquals(0, receipts(setup.origin()));
    }

    @Test void rekeyReceiptCorruptionCannotBeReturnedAsSuccessfulReplay() {
        var setup = setup();
        consume(store(), setup);
        jdbc().update("UPDATE vcl_keyed_rekey_receipts SET receipt_digest=? WHERE account_id=? AND source_key_digest=?", PINS, setup.origin().accountId(), setup.origin().digest());
        code("KEY_REKEY_RECEIPT_CORRUPT", () -> consume(store(), setup));
        assertEquals(1, store().inspect(setup.target(), PINS).revision());
    }

    @Test void selfRekeyUsesOneKeyFenceAndDoesNotResetOrDuplicateItsState() {
        var origin = key();
        store().register(origin, PINS, INITIAL);
        var lease = store().acquire(origin, PINS, UUID.randomUUID());
        var output = new Output(UUID.randomUUID(), OutputKind.REKEY, origin, INITIAL);
        store().commit(origin, lease, new Transition(UUID.randomUUID(), 1, PINS, 0, INITIAL, List.of(), null, List.of(output)));
        var transition = new Transition(output.id(), 1, output.rekeyInputDigest(origin), 1, State.parse("{\"self\":true}"));
        var receipt = store().consumeRekey(origin, lease, origin, lease, transition);
        assertEquals(2, receipt.target().committedRevision());
        assertEquals(receipt.id(), store().consumeRekey(origin, lease, origin, lease, transition).id());
        assertEquals(2, counter(origin));
    }

    @Test void expiredLeaseAfterReceiptSqlRollsBackTheEntireTransfer() {
        var setup = setup();
        jdbc().update("UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?",
                Instant.now().plusMillis(250).atOffset(ZoneOffset.UTC), setup.origin().accountId(), setup.origin().digest());
        var delayed = new JdbcKeyedStateStore(new WrappedSource(source()) {
            @Override Connection wrap(Connection connection) {
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        var result = method.invoke(connection, args);
                        if (method.getName().equals("prepareStatement") && ((String) args[0]).contains("INSERT INTO vcl_keyed_rekey_receipts")) {
                            var statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (ignored, call, values) -> {
                                try {
                                    var returned = call.invoke(statement, values);
                                    if (call.getName().equals("executeUpdate")) Thread.sleep(350);
                                    return returned;
                                } catch (InvocationTargetException error) { throw error.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
            }
        });
        code("KEY_LEASE_STALE", () -> consume(delayed, setup));
        assertUnchanged(setup);
        assertTrue(store().pendingOutputs(setup.target(), PINS, 128).isEmpty());
        assertTrue(store().dueTimers(setup.target().accountId(), 128).isEmpty());
    }

    private Setup setup() {
        var origin = key();
        var target = new Key(origin.accountId(), origin.ruleId(), origin.deploymentEpoch(), "e".repeat(64), "f".repeat(64));
        store().register(origin, PINS, INITIAL);
        store().register(target, PINS, INITIAL);
        var originLease = store().acquire(origin, PINS, UUID.randomUUID());
        var targetLease = store().acquire(target, PINS, UUID.randomUUID());
        var output = new Output(UUID.randomUUID(), OutputKind.REKEY, target, State.parse("{\"reading\":\"sample\"}"));
        store().commit(origin, originLease, new Transition(UUID.randomUUID(), 1, PINS, 0, INITIAL, List.of(), null, List.of(output)));
        var timer = new Timer(UUID.randomUUID(), TimerKind.WAIT_TIMEOUT, Instant.EPOCH, INITIAL);
        var derived = new Output(UUID.randomUUID(), OutputKind.MEASURE, null, INITIAL);
        var transition = new Transition(output.id(), 1, output.rekeyInputDigest(origin), 0,
                State.parse("{\"consumed\":true}"), List.of(timer), null, List.of(derived));
        return new Setup(origin, originLease, target, targetLease, output, transition);
    }

    private static KeyedStateStore.RekeyReceipt consume(KeyedStateStore store, Setup setup) {
        return store.consumeRekey(setup.origin(), setup.originLease(), setup.target(), setup.targetLease(), setup.transition());
    }
    private void assertUnchanged(Setup setup) {
        assertEquals(0, store().inspect(setup.target(), PINS).revision());
        assertEquals(0, receipts(setup.origin()));
        assertEquals(0, counter(setup.target()));
        assertEquals(null, store().inspectOutput(setup.origin(), PINS, setup.output().id()).receiptId());
    }
    private int receipts(Key key) { return jdbc().queryForObject("SELECT COUNT(*) FROM vcl_keyed_rekey_receipts WHERE account_id=? AND source_key_digest=?", Integer.class, key.accountId(), key.digest()); }
    private int counter(Key key) { return jdbc().queryForObject("SELECT inbox_count FROM vcl_keyed_state WHERE account_id=? AND key_digest=?", Integer.class, key.accountId(), key.digest()); }
    private void expire(Key key) { jdbc().update("UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?", Instant.EPOCH.atOffset(ZoneOffset.UTC), key.accountId(), key.digest()); }
    private static Key key() { return new Key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64), "d".repeat(64)); }
    private static void code(String code, org.junit.jupiter.api.function.Executable action) { assertEquals(code, assertThrows(VclException.class, action).code()); }
}
