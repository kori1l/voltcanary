package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.voltcanary.analytics.vcl.KeyedStateStore.Key;
import com.voltcanary.analytics.vcl.KeyedStateStore.State;
import com.voltcanary.analytics.vcl.KeyedStateStore.Transition;
import com.voltcanary.analytics.vcl.adapter.JdbcKeyedStateStore;
import com.voltcanary.vcl.VclException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

abstract class KeyedStateContract {

    private static final String PINS = "a".repeat(64);
    private static final State INITIAL = State.parse("{\"count\":\"0\"}");

    abstract DataSource source();

    private KeyedStateStore store() {
        return new JdbcKeyedStateStore(source());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(source());
    }

    @Test
    void restartReplaysExactlyOneAtomicStateAndInboxCommit() {
        var key = key();
        var store = store();
        assertEquals(0, store.register(key, PINS, INITIAL).revision());
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var transition = transition(UUID.randomUUID(), 1, 0, "1");
        assertEquals(new KeyedStateStore.Receipt(1, false), store.commit(key, lease, transition));
        var restarted = store();
        assertEquals("{\"count\":\"1\"}", restarted.inspect(key, PINS).state().payload());
        assertEquals(
            new KeyedStateStore.Receipt(1, true),
            restarted.commit(key, lease, transition)
        );
        assertEquals(1, restarted.register(key, PINS, INITIAL).revision());
        assertEquals(
            1,
            jdbc().queryForObject(
                "SELECT inbox_count FROM vcl_keyed_state WHERE account_id=? AND key_digest=?",
                Integer.class,
                key.accountId(),
                key.digest()
            )
        );
    }

    @Test
    void immutableRegistrationPinsAndAccountEpochSubjectOccurrenceAreIndependent() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        code("KEY_REGISTRATION_CHANGED", () -> store.register(key, PINS, State.parse("{}")));
        code("KEY_REGISTRATION_CHANGED", () -> store.register(key, "b".repeat(64), INITIAL));
        code("KEY_PINS_CHANGED", () -> store.inspect(key, "b".repeat(64)));
        code("KEY_PINS_CHANGED", () -> store.acquire(key, "b".repeat(64), UUID.randomUUID()));
        for (var foreign : List.of(
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
            ),
            new Key(
                key.accountId(),
                key.ruleId(),
                key.deploymentEpoch(),
                "e".repeat(64),
                key.occurrenceDigest()
            ),
            new Key(
                key.accountId(),
                key.ruleId(),
                key.deploymentEpoch(),
                key.subjectDigest(),
                "f".repeat(64)
            )
        )) {
            code("KEY_UNAVAILABLE", () -> store.inspect(foreign, PINS));
        }
    }

    @Test
    void leaseRenewalFencesPreviousWorkerAndCannotBeMovedToAnotherKey() {
        var key = key();
        var other = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        store.register(other, PINS, INITIAL);
        var holder = UUID.randomUUID();
        var first = store.acquire(key, PINS, holder);
        code("KEY_LEASE_BUSY", () -> store.acquire(key, PINS, UUID.randomUUID()));
        var renewed = store.acquire(key, PINS, holder);
        assertEquals(first.fence() + 1, renewed.fence());
        code("KEY_LEASE_STALE", () ->
            store.commit(key, first, transition(UUID.randomUUID(), 1, 0, "1"))
        );
        store.acquire(other, PINS, holder);
        code("KEY_LEASE_CHANGED", () ->
            store.commit(other, renewed, transition(UUID.randomUUID(), 1, 0, "1"))
        );
        assertFalse(store.commit(key, renewed, transition(UUID.randomUUID(), 1, 0, "1")).replay());
    }

    @Test
    void inputCorrectionsAndConflictingReplayHaveExplicitRevisions() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var input = UUID.randomUUID();
        store.commit(key, lease, transition(input, 1, 0, "1"));
        code("KEY_INPUT_CONFLICT", () -> store.commit(key, lease, transition(input, 1, 0, "2")));
        code("KEY_STATE_CONFLICT", () -> store.commit(key, lease, transition(input, 2, 0, "2")));
        assertEquals(2, store.commit(key, lease, transition(input, 2, 1, "2")).committedRevision());
        assertEquals(2, store.inspect(key, PINS).revision());
    }

    @Test
    void failedInboxInsertRollsBackTheStateAndAllowsSameIntentRetry() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var input = UUID.randomUUID();
        var transition = transition(input, 1, 0, "1");
        jdbc().execute(
            "ALTER TABLE vcl_keyed_inbox ADD CONSTRAINT vcl_test_failure CHECK (input_id <> CAST('" +
                input +
                "' AS UUID))"
        );
        try {
            code("KEY_STORAGE_UNAVAILABLE", () -> store.commit(key, lease, transition));
            assertEquals(0, store.inspect(key, PINS).revision());
            assertEquals(INITIAL.digest(), store.inspect(key, PINS).state().digest());
        } finally {
            jdbc().execute("ALTER TABLE vcl_keyed_inbox DROP CONSTRAINT vcl_test_failure");
        }
        assertEquals(new KeyedStateStore.Receipt(1, false), store.commit(key, lease, transition));
    }

    @Test
    void lostCommitAcknowledgementReplaysOriginalReceiptEvenAfterLeaseExpiry() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var transition = transition(UUID.randomUUID(), 1, 0, "1");
        var flaky = losingCommitAck();
        code("KEY_STORAGE_UNAVAILABLE", () -> flaky.commit(key, lease, transition));
        expire(key);
        assertEquals(new KeyedStateStore.Receipt(1, true), store.commit(key, lease, transition));
        code("KEY_LEASE_STALE", () ->
            store.commit(key, lease, transition(UUID.randomUUID(), 1, 1, "2"))
        );
    }

    @Test
    void concurrentSameInputCommitsOneReceiptAndStateRevision() throws Exception {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var transition = transition(UUID.randomUUID(), 1, 0, "1");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return store.commit(key, lease, transition);
            });
            var b = pool.submit(() -> {
                start.await();
                return store.commit(key, lease, transition);
            });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertTrue(first.replay() != second.replay());
            assertEquals(1, first.committedRevision());
            assertEquals(1, second.committedRevision());
        }
    }

    @Test
    void concurrentRegistrationAndDistinctInputCasHaveOneWinner() throws Exception {
        var key = key();
        var store = store();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> {
                start.await();
                return store.register(key, PINS, INITIAL);
            });
            var b = pool.submit(() -> {
                start.await();
                return store.register(key, PINS, INITIAL);
            });
            start.countDown();
            assertEquals(0, a.get(10, TimeUnit.SECONDS).revision());
            assertEquals(0, b.get(10, TimeUnit.SECONDS).revision());
            var lease = store.acquire(key, PINS, UUID.randomUUID());
            var race = new CountDownLatch(1);
            var first = pool.submit(() -> {
                race.await();
                try {
                    store.commit(key, lease, transition(UUID.randomUUID(), 1, 0, "1"));
                    return "COMMITTED";
                } catch (VclException error) {
                    return error.code();
                }
            });
            var second = pool.submit(() -> {
                race.await();
                try {
                    store.commit(key, lease, transition(UUID.randomUUID(), 1, 0, "2"));
                    return "COMMITTED";
                } catch (VclException error) {
                    return error.code();
                }
            });
            race.countDown();
            assertEquals(
                java.util.Set.of("COMMITTED", "KEY_STATE_CONFLICT"),
                java.util.Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
            );
            assertEquals(1, store.inspect(key, PINS).revision());
        }
    }

    @Test
    void freshDatabaseClockAfterLockWaitRejectsExpiredWorker() throws Exception {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var started = new CountDownLatch(1);
        var waiting = new JdbcKeyedStateStore(
            new WrappedSource(source()) {
                @Override
                Connection wrap(Connection connection) {
                    started.countDown();
                    return connection;
                }
            }
        );
        try (
            var pool = Executors.newSingleThreadExecutor();
            var blocker = source().getConnection()
        ) {
            blocker.setAutoCommit(false);
            try (
                var update = blocker.prepareStatement(
                    "UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?"
                )
            ) {
                update.setObject(1, Instant.now().plusMillis(250).atOffset(ZoneOffset.UTC));
                update.setObject(2, key.accountId());
                update.setString(3, key.digest());
                update.executeUpdate();
            }
            var future = pool.submit(() -> {
                try {
                    waiting.commit(key, lease, transition(UUID.randomUUID(), 1, 0, "1"));
                    return "COMMITTED";
                } catch (VclException error) {
                    return error.code();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(400);
            assertFalse(future.isDone());
            blocker.commit();
            assertEquals("KEY_LEASE_STALE", future.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, store.inspect(key, PINS).revision());
        assertTrue(store.acquire(key, PINS, UUID.randomUUID()).fence() > lease.fence());
    }

    @Test
    void blockedTransactionTimesOutWithoutStateOrInboxEffectsAndCanRetry() throws Exception {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var transition = transition(UUID.randomUUID(), 1, 0, "1");
        try (
            var pool = Executors.newSingleThreadExecutor();
            var blocker = source().getConnection()
        ) {
            blocker.setAutoCommit(false);
            try (
                var lock = blocker.prepareStatement(
                    "UPDATE vcl_keyed_state SET lease_fence=lease_fence WHERE account_id=? AND key_digest=?"
                )
            ) {
                lock.setObject(1, key.accountId());
                lock.setString(2, key.digest());
                lock.executeUpdate();
            }
            var result = pool.submit(() -> {
                try {
                    store.commit(key, lease, transition);
                    return "COMMITTED";
                } catch (VclException error) {
                    return error.code();
                }
            });
            assertEquals("KEY_STORAGE_UNAVAILABLE", result.get(8, TimeUnit.SECONDS));
            blocker.rollback();
        }
        assertEquals(0, store.inspect(key, PINS).revision());
        assertEquals(new KeyedStateStore.Receipt(1, false), store.commit(key, lease, transition));
    }

    @Test
    void inboxLimitAndStoredCorruptionFailClosed() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        jdbc().update(
            "UPDATE vcl_keyed_state SET inbox_count=4096 WHERE account_id=? AND key_digest=?",
            key.accountId(),
            key.digest()
        );
        code("KEY_INBOX_LIMIT", () ->
            store.commit(key, lease, transition(UUID.randomUUID(), 1, 0, "1"))
        );
        assertEquals(0, store.inspect(key, PINS).revision());
        jdbc().update(
            "UPDATE vcl_keyed_state SET state_digest=? WHERE account_id=? AND key_digest=?",
            "f".repeat(64),
            key.accountId(),
            key.digest()
        );
        code("KEY_STATE_CORRUPT", () -> store.inspect(key, PINS));
    }

    @Test
    void persistedFullKeyTupleCannotBeSubstitutedUnderTheSameHash() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        jdbc().update(
            "UPDATE vcl_keyed_state SET deployment_epoch=? WHERE account_id=? AND key_digest=?",
            UUID.randomUUID(),
            key.accountId(),
            key.digest()
        );
        code("KEY_IDENTITY_CHANGED", () -> store.inspect(key, PINS));
        code("KEY_IDENTITY_CHANGED", () -> store.acquire(key, PINS, UUID.randomUUID()));
    }

    @Test
    void timersRestoreWithTheirKeyAndStateRevisionAndDiscoverOnlyDueAccountWork() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var holder = UUID.randomUUID();
        var lease = store.acquire(key, PINS, holder);
        var early = timer(new UUID(0, 1), Instant.EPOCH);
        var later = timer(new UUID(0, 2), Instant.EPOCH.plusSeconds(1));
        var future = timer(new UUID(0, 3), Instant.parse("9999-12-31T23:59:59.999999Z"));
        store.commit(key, lease, scheduled(UUID.randomUUID(), 0, List.of(future, later, early)));
        var restarted = store();
        var restored = restarted.acquire(key, PINS, holder);
        assertEquals(3, restored.timers().size());
        assertEquals(
            List.of(early, later, future),
            restored.timers().stream().map(KeyedStateStore.ScheduledTimer::timer).toList()
        );
        assertTrue(
            restored
                .timers()
                .stream()
                .allMatch(timer -> timer.stateRevision() == 1)
        );
        var due = restarted.dueTimers(key.accountId(), 128);
        assertEquals(
            List.of(early, later),
            due
                .stream()
                .map(candidate -> candidate.scheduled().timer())
                .toList()
        );
        assertEquals(key, due.getFirst().key());
        assertEquals(PINS, due.getFirst().pinsDigest());
        assertEquals(1, restarted.dueTimers(key.accountId(), 1).size());
        assertEquals(
            due,
            restarted.dueTimers(key.accountId(), key.ruleId(), key.deploymentEpoch(), 128)
        );
        assertTrue(
            restarted
                .dueTimers(key.accountId(), UUID.randomUUID(), key.deploymentEpoch(), 128)
                .isEmpty()
        );
        assertTrue(
            restarted.dueTimers(key.accountId(), key.ruleId(), UUID.randomUUID(), 128).isEmpty()
        );
        assertTrue(restarted.dueTimers(UUID.randomUUID(), 128).isEmpty());
    }

    @Test
    void scopedWindowPagesFilterKindAndAdvanceAcrossEqualDueTimes() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var excluded = new KeyedStateStore.Timer(
            new UUID(0, 1),
            KeyedStateStore.TimerKind.WAIT_TIMEOUT,
            Instant.EPOCH,
            INITIAL
        );
        var first = new KeyedStateStore.Timer(
            new UUID(0, 2),
            KeyedStateStore.TimerKind.WINDOW_CLOSE,
            Instant.EPOCH,
            INITIAL
        );
        var second = new KeyedStateStore.Timer(
            new UUID(0, 3),
            KeyedStateStore.TimerKind.WINDOW_CLOSE,
            Instant.EPOCH,
            INITIAL
        );
        store.commit(key, lease, scheduled(UUID.randomUUID(), 0, List.of(second, excluded, first)));

        var kind = KeyedStateStore.TimerKind.WINDOW_CLOSE;
        var page = store.dueTimers(
            key.accountId(),
            key.ruleId(),
            key.deploymentEpoch(),
            kind,
            null,
            1
        );
        assertEquals(1, page.size());
        assertEquals(first, page.getFirst().scheduled().timer());
        var next = store.dueTimers(
            key.accountId(),
            key.ruleId(),
            key.deploymentEpoch(),
            kind,
            page.getFirst(),
            1
        );
        assertEquals(1, next.size());
        assertEquals(second, next.getFirst().scheduled().timer());
        assertTrue(
            store
                .dueTimers(
                    key.accountId(),
                    key.ruleId(),
                    key.deploymentEpoch(),
                    kind,
                    next.getFirst(),
                    1
                )
                .isEmpty()
        );
        assertThrows(IllegalArgumentException.class, () ->
            store.dueTimers(
                key.accountId(),
                key.ruleId(),
                UUID.randomUUID(),
                kind,
                page.getFirst(),
                1
            )
        );
    }

    @Test
    void timerAndObservationInputsHaveSeparateReceiptNamespacesAndAtomicReplay() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var timer = timer(UUID.randomUUID(), Instant.EPOCH);
        var observation = scheduled(timer.id(), 0, List.of(timer));
        store.commit(key, lease, observation);
        var consumed = consume(timer, 1, 1);
        assertEquals(new KeyedStateStore.Receipt(2, false), store.commit(key, lease, consumed));
        assertEquals(new KeyedStateStore.Receipt(2, true), store().commit(key, lease, consumed));
        assertEquals(new KeyedStateStore.Receipt(1, true), store.commit(key, lease, observation));
        assertTrue(store.dueTimers(key.accountId(), 128).isEmpty());
        assertEquals(2, store.inspect(key, PINS).revision());
    }

    @Test
    void futureOrChangedTimerDigestCannotConsumeASchedule() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var holder = UUID.randomUUID();
        var lease = store.acquire(key, PINS, holder);
        var future = timer(UUID.randomUUID(), Instant.parse("9999-01-01T00:00:00Z"));
        var due = timer(UUID.randomUUID(), Instant.EPOCH);
        store.commit(key, lease, scheduled(UUID.randomUUID(), 0, List.of(future, due)));
        code("KEY_TIMER_CHANGED", () -> store.commit(key, lease, consume(future, 1, 1)));
        var bad = new Transition(
            due.id(),
            1,
            "f".repeat(64),
            1,
            INITIAL,
            List.of(),
            new KeyedStateStore.TimerInput(due.id(), 1)
        );
        code("KEY_TIMER_INPUT_CHANGED", () -> store.commit(key, lease, bad));
        assertEquals(1, store.inspect(key, PINS).revision());
        assertEquals(2, store.acquire(key, PINS, holder).timers().size());
    }

    @Test
    void correctionInvalidatesOldDiscoveryEvenWhenTheTimerRemainsDue() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var original = timer(UUID.randomUUID(), Instant.EPOCH);
        store.commit(key, lease, scheduled(UUID.randomUUID(), 0, List.of(original)));
        var discovered = store.dueTimers(key.accountId(), 128).getFirst();
        var correction = timer(original.id(), Instant.EPOCH.plusNanos(1000));
        store.commit(key, lease, scheduled(UUID.randomUUID(), 1, List.of(correction)));
        code("KEY_TIMER_CHANGED", () ->
            store.commit(key, lease, consume(discovered.scheduled().timer(), 1, 2))
        );
        assertEquals(3, store.commit(key, lease, consume(correction, 2, 2)).committedRevision());
        assertTrue(store.dueTimers(key.accountId(), 128).isEmpty());
    }

    @Test
    void inboxFailureRollsBackReplacementTimersAndTimerConsumption() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var holder = UUID.randomUUID();
        var lease = store.acquire(key, PINS, holder);
        var original = timer(UUID.randomUUID(), Instant.EPOCH);
        store.commit(key, lease, scheduled(UUID.randomUUID(), 0, List.of(original)));
        var consumed = consume(original, 1, 1);
        jdbc().execute(
            "ALTER TABLE vcl_keyed_inbox ADD CONSTRAINT vcl_test_timer_failure CHECK (account_id <> CAST('" +
                key.accountId() +
                "' AS UUID) OR input_kind <> 'TIMER')"
        );
        try {
            code("KEY_STORAGE_UNAVAILABLE", () -> store.commit(key, lease, consumed));
            assertEquals(1, store.inspect(key, PINS).revision());
            assertEquals(
                original,
                store.dueTimers(key.accountId(), 128).getFirst().scheduled().timer()
            );
        } finally {
            jdbc().execute("ALTER TABLE vcl_keyed_inbox DROP CONSTRAINT vcl_test_timer_failure");
        }
        var replacement = timer(UUID.randomUUID(), Instant.EPOCH.plusSeconds(1));
        var input = UUID.randomUUID();
        var replace = scheduled(input, 1, List.of(replacement));
        jdbc().execute(
            "ALTER TABLE vcl_keyed_inbox ADD CONSTRAINT vcl_test_timer_failure CHECK (input_id <> CAST('" +
                input +
                "' AS UUID))"
        );
        try {
            code("KEY_STORAGE_UNAVAILABLE", () -> store.commit(key, lease, replace));
            assertEquals(
                original,
                store.dueTimers(key.accountId(), 128).getFirst().scheduled().timer()
            );
        } finally {
            jdbc().execute("ALTER TABLE vcl_keyed_inbox DROP CONSTRAINT vcl_test_timer_failure");
        }
        assertEquals(2, store.commit(key, lease, replace).committedRevision());
        assertEquals(replacement, store.acquire(key, PINS, holder).timers().getFirst().timer());
    }

    @Test
    void concurrentTimerConsumptionReplaysOneReceipt() throws Exception {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var timer = timer(UUID.randomUUID(), Instant.EPOCH);
        store.commit(key, lease, scheduled(UUID.randomUUID(), 0, List.of(timer)));
        var transition = consume(timer, 1, 1);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return store.commit(key, lease, transition);
            });
            var b = pool.submit(() -> {
                start.await();
                return store.commit(key, lease, transition);
            });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertTrue(first.replay() != second.replay());
            assertEquals(2, first.committedRevision());
            assertEquals(2, second.committedRevision());
            assertTrue(store.dueTimers(key.accountId(), 128).isEmpty());
        }
    }

    @Test
    void lostTimerCommitAckRestartsFromOneReceiptWithoutResurrectingTheSchedule() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var lease = store.acquire(key, PINS, UUID.randomUUID());
        var timer = timer(UUID.randomUUID(), Instant.EPOCH);
        store.commit(key, lease, scheduled(UUID.randomUUID(), 0, List.of(timer)));
        var transition = consume(timer, 1, 1);
        code("KEY_STORAGE_UNAVAILABLE", () -> losingCommitAck().commit(key, lease, transition));
        expire(key);
        var restarted = store();
        assertEquals(
            new KeyedStateStore.Receipt(2, true),
            restarted.commit(key, lease, transition)
        );
        assertEquals(2, restarted.inspect(key, PINS).revision());
        assertTrue(restarted.dueTimers(key.accountId(), 128).isEmpty());
        assertTrue(restarted.acquire(key, PINS, UUID.randomUUID()).timers().isEmpty());
    }

    @Test
    void timerSnapshotDigestIsOrderIndependentAndRejectsDuplicatesLimitsAndRearm() {
        var a = timer(UUID.randomUUID(), Instant.EPOCH);
        var b = timer(UUID.randomUUID(), Instant.EPOCH.plusNanos(1000));
        var input = UUID.randomUUID();
        assertEquals(
            scheduled(input, 0, List.of(a, b)).digest(),
            scheduled(input, 0, List.of(b, a)).digest()
        );
        assertThrows(IllegalArgumentException.class, () -> scheduled(input, 0, List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () ->
            scheduled(input, 0, java.util.Collections.nCopies(65, a))
        );
        assertThrows(VclException.class, () ->
            timer(UUID.randomUUID(), Instant.EPOCH.plusNanos(1))
        );
        assertThrows(IllegalArgumentException.class, () ->
            new Transition(
                a.id(),
                1,
                a.digest(),
                1,
                INITIAL,
                List.of(a),
                new KeyedStateStore.TimerInput(a.id(), 1)
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new Transition(
                UUID.randomUUID(),
                1,
                a.digest(),
                1,
                INITIAL,
                List.of(),
                new KeyedStateStore.TimerInput(a.id(), 1)
            )
        );
        var huge = new ArrayList<KeyedStateStore.Timer>();
        for (int i = 0; i < 5; i++) huge.add(
            new KeyedStateStore.Timer(
                UUID.randomUUID(),
                KeyedStateStore.TimerKind.WAIT_TIMEOUT,
                Instant.EPOCH,
                State.parse("{\"x\":\"" + "x".repeat(60000) + "\"}")
            )
        );
        assertThrows(IllegalArgumentException.class, () -> scheduled(input, 0, huge));
    }

    @Test
    void timerStorageRevisionCorruptionFailsAcquisitionAndRollsBackLeaseChange() {
        var key = key();
        var store = store();
        store.register(key, PINS, INITIAL);
        var holder = UUID.randomUUID();
        var lease = store.acquire(key, PINS, holder);
        var timer = timer(UUID.randomUUID(), Instant.EPOCH);
        store.commit(key, lease, scheduled(UUID.randomUUID(), 0, List.of(timer)));
        jdbc().update(
            "UPDATE vcl_keyed_timers SET scheduled_revision=2 WHERE account_id=? AND key_digest=?",
            key.accountId(),
            key.digest()
        );
        code("KEY_TIMER_REVISION_CHANGED", () -> store.acquire(key, PINS, holder));
        code("KEY_TIMER_REVISION_CHANGED", () -> store.dueTimers(key.accountId(), 128));
        jdbc().update(
            "UPDATE vcl_keyed_timers SET scheduled_revision=1 WHERE account_id=? AND key_digest=?",
            key.accountId(),
            key.digest()
        );
        assertEquals(2, store.commit(key, lease, consume(timer, 1, 1)).committedRevision());
        assertThrows(IllegalArgumentException.class, () -> store.dueTimers(key.accountId(), 0));
        assertThrows(IllegalArgumentException.class, () -> store.dueTimers(key.accountId(), 129));
    }

    private static KeyedStateStore.Timer timer(UUID id, Instant due) {
        return new KeyedStateStore.Timer(
            id,
            KeyedStateStore.TimerKind.WAIT_TIMEOUT,
            due,
            State.parse("{\"reason\":\"IDLE\"}")
        );
    }

    private static Transition scheduled(
        UUID input,
        long expectedRevision,
        List<KeyedStateStore.Timer> timers
    ) {
        return new Transition(input, 1, "e".repeat(64), expectedRevision, INITIAL, timers, null);
    }

    private static Transition consume(
        KeyedStateStore.Timer timer,
        long scheduledRevision,
        long expectedRevision
    ) {
        return new Transition(
            timer.id(),
            scheduledRevision,
            timer.digest(),
            expectedRevision,
            INITIAL,
            List.of(),
            new KeyedStateStore.TimerInput(timer.id(), scheduledRevision)
        );
    }

    @Test
    void stateIsBoundedCanonicalExactAndRedacted() {
        var state = State.parse("{\"z\":null,\"a\":[true,{\"value\":\"9223372036854775807\"}]}");
        assertEquals(
            "{\"a\":[true,{\"value\":\"9223372036854775807\"}],\"z\":null}",
            state.payload()
        );
        assertEquals(state.digest(), State.restore(state.payload(), state.digest()).digest());
        assertFalse(state.toString().contains("922337"));
        for (String invalid : List.of(
            "{\"a\":1}",
            "[]",
            "{\"a\":true,\"a\":false}",
            "{}{}",
            "{\"x\":\"\\ud800\"}",
            "{\"x\":".repeat(40) + "{}" + "}".repeat(40)
        )) {
            assertThrows(VclException.class, () -> State.parse(invalid));
        }
        code("KEY_STATE_LIMIT", () -> State.parse("{\"x\":\"" + "Ж".repeat(40000) + "\"}"));
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

    private static Transition transition(
        UUID input,
        long inputRevision,
        long expectedRevision,
        String value
    ) {
        return new Transition(
            input,
            inputRevision,
            "e".repeat(64),
            expectedRevision,
            State.parse("{\"count\":\"" + value + "\"}")
        );
    }

    private static void code(String expected, Runnable work) {
        assertEquals(expected, assertThrows(VclException.class, work::run).code());
    }

    protected KeyedStateStore losingCommitAck() {
        return new JdbcKeyedStateStore(
            new WrappedSource(source()) {
                @Override
                Connection wrap(Connection connection) {
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> {
                            try {
                                var result = method.invoke(connection, args);
                                if (method.getName().equals("commit")) throw new SQLException(
                                    "lost acknowledgement",
                                    "08006"
                                );
                                return result;
                            } catch (java.lang.reflect.InvocationTargetException error) {
                                throw error.getCause();
                            }
                        }
                    );
                }
            }
        );
    }

    protected abstract static class WrappedSource extends AbstractDataSource {

        private final DataSource source;

        WrappedSource(DataSource source) {
            this.source = source;
        }

        abstract Connection wrap(Connection connection) throws SQLException;

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(source.getConnection());
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return wrap(source.getConnection(user, password));
        }
    }
}
