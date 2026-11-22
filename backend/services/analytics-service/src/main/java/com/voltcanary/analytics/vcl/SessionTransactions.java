package com.voltcanary.analytics.vcl;

import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Shared keyed observation and exact timer transactions for typed session profiles. */
final class SessionTransactions {

    record Update(
        KeyedStateStore.State next,
        Instant openedAt,
        Instant deadline,
        boolean emitTerminal,
        boolean correctedTimeout
    ) {}

    private final KeyedStateStore store;
    private final KeyedStateStore.Key key;
    private final String pins;
    private final Runnable authorize;
    private final DurableWindowSession.Commit commit;
    private final UnaryOperator<Instant> timeoutDue;
    private final UUID timerId;

    SessionTransactions(
        KeyedStateStore store,
        KeyedStateStore.Key key,
        String profile,
        String planDigest,
        String ownerPinsDigest,
        Runnable authorize,
        DurableWindowSession.Commit commit,
        UnaryOperator<Instant> timeoutDue
    ) {
        require(
            ownerPinsDigest != null && ownerPinsDigest.matches("[0-9a-f]{64}"),
            "SESSION_PINS_INVALID"
        );
        this.store = java.util.Objects.requireNonNull(store);
        this.key = key;
        this.authorize = java.util.Objects.requireNonNull(authorize);
        this.commit = java.util.Objects.requireNonNull(commit);
        this.timeoutDue = java.util.Objects.requireNonNull(timeoutDue);
        pins = VclCanonical.sha256(
            String.join("\n", profile, planDigest, key.digest(), ownerPinsDigest)
        );
        timerId = id("timeout");
    }

    String pins() {
        return pins;
    }

    KeyedStateStore.Receipt observe(
        UUID inputId,
        String inputDigest,
        UUID holder,
        KeyedStateStore.State initial,
        Function<KeyedStateStore.State, Update> evaluate
    ) {
        authorize.run();
        if (initial != null) store.register(key, pins, initial);
        var replay = replay(KeyedStateStore.InputKind.OBSERVATION, inputId, 1, inputDigest);
        if (replay != null) return replay;
        var lease = store.acquire(key, pins, holder);
        require(initial != null || lease.snapshot().revision() > 0, "SESSION_STATE_UNAVAILABLE");
        replay = replay(KeyedStateStore.InputKind.OBSERVATION, inputId, 1, inputDigest);
        if (replay != null) return replay;
        var update = evaluate.apply(lease.snapshot().state());
        var timers =
            update.deadline() == null
                ? List.<KeyedStateStore.Timer>of()
                : List.of(
                      new KeyedStateStore.Timer(
                          timerId,
                          KeyedStateStore.TimerKind.SESSION_TIMEOUT,
                          timeoutDue.apply(update.deadline()),
                          update.next()
                      )
                  );
        authorize.run();
        return commit.apply(
            key,
            lease,
            new KeyedStateStore.Transition(
                inputId,
                1,
                inputDigest,
                lease.snapshot().revision(),
                update.next(),
                timers,
                null,
                output(update)
            ),
            update.openedAt()
        );
    }

    KeyedStateStore.Receipt timeout(
        KeyedStateStore.DueTimer due,
        UUID holder,
        BiFunction<KeyedStateStore.State, Instant, Update> evaluate
    ) {
        var scheduled = due.scheduled();
        var timer = scheduled.timer();
        require(
            key.equals(due.key()) &&
                pins.equals(due.pinsDigest()) &&
                timerId.equals(timer.id()) &&
                timer.kind() == KeyedStateStore.TimerKind.SESSION_TIMEOUT,
            "SESSION_TIMER_CHANGED"
        );
        authorize.run();
        var replay = replay(
            KeyedStateStore.InputKind.TIMER,
            timerId,
            scheduled.stateRevision(),
            timer.digest()
        );
        if (replay != null) return replay;
        var lease = store.acquire(key, pins, holder);
        replay = replay(
            KeyedStateStore.InputKind.TIMER,
            timerId,
            scheduled.stateRevision(),
            timer.digest()
        );
        if (replay != null) return replay;
        require(
            lease.timers().equals(List.of(scheduled)) &&
                lease.snapshot().state().equals(timer.payload()),
            "SESSION_TIMER_CHANGED"
        );
        var update = evaluate.apply(lease.snapshot().state(), timer.dueAt());
        require(update.deadline() == null, "SESSION_TIMER_CHANGED");
        authorize.run();
        return commit.apply(
            key,
            lease,
            new KeyedStateStore.Transition(
                timerId,
                scheduled.stateRevision(),
                timer.digest(),
                lease.snapshot().revision(),
                update.next(),
                List.of(),
                new KeyedStateStore.TimerInput(timerId, scheduled.stateRevision()),
                output(update)
            ),
            update.openedAt()
        );
    }

    private KeyedStateStore.Receipt replay(
        KeyedStateStore.InputKind kind,
        UUID id,
        long revision,
        String digest
    ) {
        var stored = store.inputReceipt(key, pins, kind, id, revision);
        if (stored.isEmpty()) return null;
        require(digest.equals(stored.get().inputDigest()), "KEY_INPUT_CONFLICT");
        authorize.run();
        return new KeyedStateStore.Receipt(stored.get().committedRevision(), true);
    }

    private List<KeyedStateStore.Output> output(Update update) {
        String purpose = update.correctedTimeout()
            ? "outcome-correction:" + update.next().digest()
            : "outcome";
        return update.emitTerminal()
            ? List.of(
                  new KeyedStateStore.Output(
                      id(purpose),
                      KeyedStateStore.OutputKind.MEASURE,
                      null,
                      update.next()
                  )
              )
            : List.of();
    }

    private UUID id(String purpose) {
        return UUID.nameUUIDFromBytes((pins + "\n" + purpose).getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new VclException(code, "");
    }
}
