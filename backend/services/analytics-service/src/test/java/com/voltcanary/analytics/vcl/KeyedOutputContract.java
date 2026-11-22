package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.voltcanary.analytics.vcl.KeyedStateStore.Acknowledgement;
import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class KeyedOutputContract extends KeyedStateContract {

    private static final String PINS = "a".repeat(64);
    private static final State INITIAL = State.parse("{}");

    private KeyedStateStore store() {
        return new JdbcKeyedStateStore(source());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(source());
    }

    @Test
    void outputKindsAndFullRekeyTargetSurviveRestartInTheStateTransaction() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var holder = UUID.randomUUID();
        var lease = store.acquire(key, PINS, holder);
        var measure = output(UUID.randomUUID(), "1");
        var target = new Key(
            key.accountId(),
            key.ruleId(),
            key.deploymentEpoch(),
            "e".repeat(64),
            "f".repeat(64)
        );
        var rekey = new Output(
            UUID.randomUUID(),
            OutputKind.REKEY,
            target,
            State.parse("{\"value\":\"2\"}")
        );
        var alert = new Output(
            UUID.randomUUID(),
            OutputKind.ALERT_PROPOSAL,
            null,
            State.parse("{\"decision\":\"UNKNOWN\"}")
        );
        var timer = timer();
        var transition = transition(
            UUID.randomUUID(),
            0,
            List.of(timer),
            List.of(measure, rekey, alert)
        );
        assertEquals(1, store.commit(key, lease, transition).committedRevision());
        var restarted = store();
        assertEquals(3, restarted.pendingOutputs(key, PINS, 128).size());
        assertEquals(rekey, restarted.inspectOutput(key, PINS, rekey.id()).output());
        assertEquals(1, restarted.inspectOutput(key, PINS, measure.id()).stateRevision());
        assertEquals(
            List.of(timer),
            restarted
                .acquire(key, PINS, holder)
                .timers()
                .stream()
                .map(KeyedStateStore.ScheduledTimer::timer)
                .toList()
        );
        assertEquals(1, restarted.pendingOutputs(key, PINS, 1).size());
    }

    @Test
    void lostCommitAckLeavesOneOutputAndAnExactInputReplay() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var output = output(UUID.randomUUID(), "1");
        var transition = transition(UUID.randomUUID(), 0, List.of(), List.of(output));
        code("KEY_STORAGE_UNAVAILABLE", () -> losingCommitAck().commit(key, lease, transition));
        expire(key);
        assertEquals(new KeyedStateStore.Receipt(1, true), store().commit(key, lease, transition));
        assertEquals(1, store.pendingOutputs(key, PINS, 128).size());
        assertEquals(1, count(key));
    }

    @Test
    void immutableOutputIdentityReusesExactBytesAndRejectsChangedContent() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var output = output(UUID.randomUUID(), "1");
        store.commit(key, lease, transition(UUID.randomUUID(), 0, List.of(), List.of(output)));
        store.commit(key, lease, transition(UUID.randomUUID(), 1, List.of(), List.of(output)));
        assertEquals(1, count(key));
        assertEquals(1, store.inspectOutput(key, PINS, output.id()).stateRevision());
        code("KEY_OUTPUT_CONFLICT", () ->
            store.commit(
                key,
                lease,
                transition(UUID.randomUUID(), 2, List.of(), List.of(output(output.id(), "2")))
            )
        );
        assertEquals(2, store.inspect(key, PINS).revision());
    }

    @Test
    void failedInboxWriteRollsBackStateTimersOutputsAndRetainedCounter() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var holder = UUID.randomUUID();
        var lease = store.acquire(key, PINS, holder);
        var original = timer();
        store.commit(key, lease, transition(UUID.randomUUID(), 0, List.of(original), List.of()));
        var output = output(UUID.randomUUID(), "1");
        var replacement = timer();
        var input = UUID.randomUUID();
        var transition = transition(input, 1, List.of(replacement), List.of(output));
        jdbc().execute(
            "ALTER TABLE vcl_keyed_inbox ADD CONSTRAINT vcl_test_output_failure CHECK (input_id <> CAST('" +
                input +
                "' AS UUID))"
        );
        try {
            code("KEY_STORAGE_UNAVAILABLE", () -> store.commit(key, lease, transition));
            assertEquals(1, store.inspect(key, PINS).revision());
            assertTrue(store.pendingOutputs(key, PINS, 128).isEmpty());
            assertEquals(0, count(key));
            assertEquals(
                original,
                store.dueTimers(key.accountId(), 128).getFirst().scheduled().timer()
            );
        } finally {
            jdbc().execute("ALTER TABLE vcl_keyed_inbox DROP CONSTRAINT vcl_test_output_failure");
        }
        assertEquals(2, store.commit(key, lease, transition).committedRevision());
        assertEquals(replacement, store.acquire(key, PINS, holder).timers().getFirst().timer());
        assertEquals(1, count(key));
    }

    @Test
    void failedOutputBatchDoesNotLeaveItsSuccessfulPrefix() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var first = output(new UUID(0, 1), "1");
        var second = output(new UUID(0, 2), "2");
        var transition = transition(UUID.randomUUID(), 0, List.of(timer()), List.of(first, second));
        jdbc().execute(
            "ALTER TABLE vcl_keyed_outbox ADD CONSTRAINT vcl_test_output_batch CHECK (account_id <> CAST('" +
                key.accountId() +
                "' AS UUID) OR output_id <> CAST('" +
                second.id() +
                "' AS UUID))"
        );
        try {
            code("KEY_STORAGE_UNAVAILABLE", () -> store.commit(key, lease, transition));
            assertEquals(0, store.inspect(key, PINS).revision());
            assertEquals(0, count(key));
            assertTrue(store.pendingOutputs(key, PINS, 128).isEmpty());
            assertTrue(store.dueTimers(key.accountId(), 128).isEmpty());
        } finally {
            jdbc().execute("ALTER TABLE vcl_keyed_outbox DROP CONSTRAINT vcl_test_output_batch");
        }
        assertEquals(1, store.commit(key, lease, transition).committedRevision());
        assertEquals(2, store.pendingOutputs(key, PINS, 128).size());
    }

    @Test
    void retainedOutboxBudgetFailsWithoutEvictingInputOrAdvancingState() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        jdbc().update(
            "UPDATE vcl_keyed_state SET outbox_count=4096 WHERE account_id=? AND key_digest=?",
            key.accountId(),
            key.digest()
        );
        code("KEY_OUTBOX_LIMIT", () ->
            store.commit(
                key,
                lease,
                transition(UUID.randomUUID(), 0, List.of(), List.of(output(UUID.randomUUID(), "1")))
            )
        );
        assertEquals(0, store.inspect(key, PINS).revision());
        assertEquals(4096, count(key));
    }

    @Test
    void rekeyCannotCrossAccountRuleOrDeploymentAndPrivateReadsRequirePins() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        for (var target : List.of(
            new Key(
                UUID.randomUUID(),
                key.ruleId(),
                key.deploymentEpoch(),
                key.subjectDigest(),
                key.occurrenceDigest()
            ),
            new Key(
                key.accountId(),
                UUID.randomUUID(),
                key.deploymentEpoch(),
                key.subjectDigest(),
                key.occurrenceDigest()
            ),
            new Key(
                key.accountId(),
                key.ruleId(),
                UUID.randomUUID(),
                key.subjectDigest(),
                key.occurrenceDigest()
            )
        )) {
            var output = new Output(UUID.randomUUID(), OutputKind.REKEY, target, INITIAL);
            code("KEY_OUTPUT_TARGET_CHANGED", () ->
                store.commit(
                    key,
                    lease,
                    transition(UUID.randomUUID(), 0, List.of(), List.of(output))
                )
            );
        }
        assertEquals(0, store.inspect(key, PINS).revision());
        code("KEY_PINS_CHANGED", () -> store.pendingOutputs(key, "b".repeat(64), 128));
        code("KEY_PINS_CHANGED", () -> store.inspectOutput(key, "b".repeat(64), UUID.randomUUID()));
        code("KEY_UNAVAILABLE", () -> store.pendingOutputs(key(), PINS, 128));
        assertThrows(IllegalArgumentException.class, () ->
            new Output(UUID.randomUUID(), OutputKind.REKEY, null, INITIAL)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new Output(UUID.randomUUID(), OutputKind.MEASURE, key, INITIAL)
        );
    }

    @Test
    void acknowledgementIsImmutableFencedMetadataAndDoesNotRepublishOrFreeRetention() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var holder = UUID.randomUUID();
        var lease = store.acquire(key, PINS, holder);
        var output = output(UUID.randomUUID(), "1");
        store.commit(key, lease, transition(UUID.randomUUID(), 0, List.of(), List.of(output)));
        var ack = ack(output);
        assertEquals(1, store.acknowledge(key, lease, List.of(ack)));
        assertEquals(0, store.acknowledge(key, lease, List.of(ack)));
        assertEquals(ack.receiptId(), store.inspectOutput(key, PINS, output.id()).receiptId());
        assertEquals(
            ack.receiptDigest(),
            store.inspectOutput(key, PINS, output.id()).receiptDigest()
        );
        assertTrue(store.pendingOutputs(key, PINS, 128).isEmpty());
        assertEquals(1, count(key));
        code("KEY_OUTPUT_ACK_CONFLICT", () -> store.acknowledge(key, lease, List.of(ack(output))));
        var renewed = store.acquire(key, PINS, holder);
        code("KEY_LEASE_STALE", () -> store.acknowledge(key, lease, List.of(ack)));
        store.commit(key, renewed, transition(UUID.randomUUID(), 1, List.of(), List.of(output)));
        assertTrue(store.pendingOutputs(key, PINS, 128).isEmpty());
        assertEquals(1, count(key));
        expire(key);
        code("KEY_LEASE_STALE", () -> store.acknowledge(key, renewed, List.of(ack)));
        var fresh = store.acquire(key, PINS, UUID.randomUUID());
        assertEquals(0, store.acknowledge(key, fresh, List.of(ack)));
    }

    @Test
    void invalidAckBatchRollsBackItsPrefixAndLostAckRecoversTheOriginalOwnerReceipt() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var first = output(UUID.randomUUID(), "1");
        var second = output(UUID.randomUUID(), "2");
        store.commit(
            key,
            lease,
            transition(UUID.randomUUID(), 0, List.of(), List.of(first, second))
        );
        var a = ack(first);
        var b = ack(second);
        var invalid = new Acknowledgement(
            b.outputId(),
            "f".repeat(64),
            b.receiptId(),
            b.receiptDigest()
        );
        code("KEY_OUTPUT_CHANGED", () -> store.acknowledge(key, lease, List.of(a, invalid)));
        assertEquals(2, store.pendingOutputs(key, PINS, 128).size());
        code("KEY_STORAGE_UNAVAILABLE", () ->
            losingCommitAck().acknowledge(key, lease, List.of(a, b))
        );
        assertTrue(store().pendingOutputs(key, PINS, 128).isEmpty());
        assertEquals(0, store().acknowledge(key, lease, List.of(a, b)));
        assertEquals(a.receiptId(), store.inspectOutput(key, PINS, first.id()).receiptId());
        assertEquals(1, store.inspect(key, PINS).revision());
    }

    @Test
    void concurrentAckUsesOneImmutableReceipt() throws Exception {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var output = output(UUID.randomUUID(), "1");
        store.commit(key, lease, transition(UUID.randomUUID(), 0, List.of(), List.of(output)));
        var ack = ack(output);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return store.acknowledge(key, lease, List.of(ack));
            });
            var b = pool.submit(() -> {
                start.await();
                return store.acknowledge(key, lease, List.of(ack));
            });
            start.countDown();
            assertEquals(
                Set.of(0, 1),
                Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
            );
        }
    }

    @Test
    void outputCorruptionAndImpossibleRevisionAreRejected() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var output = output(UUID.randomUUID(), "1");
        store.commit(key, lease, transition(UUID.randomUUID(), 0, List.of(), List.of(output)));
        jdbc().update(
            "UPDATE vcl_keyed_outbox SET output_digest=? WHERE account_id=? AND key_digest=?",
            "f".repeat(64),
            key.accountId(),
            key.digest()
        );
        code("KEY_OUTPUT_CORRUPT", () -> store.pendingOutputs(key, PINS, 128));
        jdbc().update(
            "UPDATE vcl_keyed_outbox SET output_digest=?,state_revision=2 WHERE account_id=? AND key_digest=?",
            output.digest(),
            key.accountId(),
            key.digest()
        );
        code("KEY_OUTPUT_REVISION_CHANGED", () -> store.inspectOutput(key, PINS, output.id()));
    }

    @Test
    void outputVectorBudgetDigestAndAcknowledgementLimitsAreExplicit() {
        var a = output(UUID.randomUUID(), "1");
        var b = output(UUID.randomUUID(), "2");
        var input = UUID.randomUUID();
        assertEquals(
            transition(input, 0, List.of(), List.of(a, b)).digest(),
            transition(input, 0, List.of(), List.of(b, a)).digest()
        );
        assertThrows(IllegalArgumentException.class, () ->
            transition(input, 0, List.of(), List.of(a, a))
        );
        assertThrows(IllegalArgumentException.class, () ->
            transition(input, 0, List.of(), java.util.Collections.nCopies(65, a))
        );
        var huge = new ArrayList<Output>();
        for (int i = 0; i < 5; i++) huge.add(
            new Output(
                UUID.randomUUID(),
                OutputKind.MEASURE,
                null,
                State.parse("{\"x\":\"" + "x".repeat(60000) + "\"}")
            )
        );
        assertThrows(IllegalArgumentException.class, () -> transition(input, 0, List.of(), huge));
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var ack = ack(a);
        assertThrows(IllegalArgumentException.class, () ->
            store.acknowledge(key, lease, List.of(ack, ack))
        );
        assertThrows(IllegalArgumentException.class, () -> store.pendingOutputs(key, PINS, 129));
        code("KEY_OUTPUT_UNAVAILABLE", () -> store.acknowledge(key, lease, List.of(ack)));
        assertFalse(a.toString().contains("value"));
    }

    @Test
    void leaseExpiryDuringOutputWriteRollsBackBeforeCommit() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        jdbc().update(
            "UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?",
            Instant.now().plusSeconds(1).atOffset(ZoneOffset.UTC),
            key.accountId(),
            key.digest()
        );
        var paused = new AtomicBoolean();
        var slow = new JdbcKeyedStateStore(
            new WrappedSource(source()) {
                @Override
                Connection wrap(Connection connection) {
                    return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> {
                            try {
                                var result = method.invoke(connection, args);
                                if (
                                    method.getName().equals("prepareStatement") &&
                                    args[0].toString().contains("INSERT INTO vcl_keyed_outbox")
                                ) {
                                    var statement = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(
                                        PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] { PreparedStatement.class },
                                        (p, m, a) -> {
                                            try {
                                                var value = m.invoke(statement, a);
                                                if (m.getName().equals("executeBatch")) {
                                                    paused.set(true);
                                                    Thread.sleep(1200);
                                                }
                                                return value;
                                            } catch (InvocationTargetException error) {
                                                throw error.getCause();
                                            }
                                        }
                                    );
                                }
                                return result;
                            } catch (InvocationTargetException error) {
                                throw error.getCause();
                            }
                        }
                    );
                }
            }
        );
        var transition = transition(
            UUID.randomUUID(),
            0,
            List.of(timer()),
            List.of(output(UUID.randomUUID(), "1"))
        );
        code("KEY_LEASE_STALE", () -> slow.commit(key, lease, transition));
        assertTrue(paused.get());
        assertEquals(0, store.inspect(key, PINS).revision());
        assertEquals(0, count(key));
        assertTrue(store.pendingOutputs(key, PINS, 128).isEmpty());
        assertTrue(store.acquire(key, PINS, UUID.randomUUID()).timers().isEmpty());
    }

    private int count(Key key) {
        return jdbc().queryForObject(
            "SELECT outbox_count FROM vcl_keyed_state WHERE account_id=? AND key_digest=?",
            Integer.class,
            key.accountId(),
            key.digest()
        );
    }

    private void expire(Key key) {
        jdbc().update(
            "UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?",
            Instant.EPOCH.atOffset(ZoneOffset.UTC),
            key.accountId(),
            key.digest()
        );
    }

    private static Key key() {
        return new Key(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "c".repeat(64),
            "d".repeat(64)
        );
    }

    private static Output output(UUID id, String value) {
        return new Output(
            id,
            OutputKind.MEASURE,
            null,
            State.parse("{\"value\":\"" + value + "\"}")
        );
    }

    private static Timer timer() {
        return new Timer(UUID.randomUUID(), TimerKind.WAIT_TIMEOUT, Instant.EPOCH, INITIAL);
    }

    private static Acknowledgement ack(Output output) {
        return new Acknowledgement(output.id(), output.digest(), UUID.randomUUID(), "e".repeat(64));
    }

    private static Transition transition(
        UUID input,
        long revision,
        List<Timer> timers,
        List<Output> outputs
    ) {
        return new Transition(input, 1, "b".repeat(64), revision, INITIAL, timers, null, outputs);
    }

    private static void code(String expected, Runnable work) {
        assertEquals(expected, assertThrows(VclException.class, work::run).code());
    }
}
