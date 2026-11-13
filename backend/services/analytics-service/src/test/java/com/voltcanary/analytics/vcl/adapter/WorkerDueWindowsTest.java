package com.voltcanary.analytics.vcl.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voltcanary.analytics.vcl.KeyedStateStore;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerDueWindowsTest {
    private static final KeyedStateStore.TimerKind WINDOW_CLOSE = KeyedStateStore.TimerKind.WINDOW_CLOSE;
    private final UUID account = UUID.randomUUID(), rule = UUID.randomUUID(), epoch = UUID.randomUUID();
    private final JdbcRuleDeployments.Deployment active = new JdbcRuleDeployments.Deployment(UUID.randomUUID(), account,
            rule, epoch, UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), JdbcRuleDeployments.State.ACTIVE, 1);
    private final WorkerDeploymentAdmission admission = mock(WorkerDeploymentAdmission.class);
    private final WorkerRuleCompilationSnapshots recovery = mock(WorkerRuleCompilationSnapshots.class);
    private final KeyedStateStore store = mock(KeyedStateStore.class);
    private final VclCompiler compiler = mock(VclCompiler.class);

    @Test void consumesOnlyDueTimerForTheActiveDeploymentAsDiagnosticOutput() {
        var key = new KeyedStateStore.Key(account, rule, epoch, "b".repeat(64), "c".repeat(64));
        var timer = new KeyedStateStore.Timer(UUID.randomUUID(), KeyedStateStore.TimerKind.WINDOW_CLOSE,
                Instant.EPOCH, KeyedStateStore.State.parse("{}"));
        var due = new KeyedStateStore.DueTimer(key, "d".repeat(64), new KeyedStateStore.ScheduledTimer(timer, 1));
        var restored = mock(DurableJournalWindow.class);
        var output = mock(KeyedStateStore.OutputStatus.class);
        when(admission.candidates(8, 0)).thenReturn(List.of(active));
        when(admission.inspect(active)).thenReturn(new WorkerDeploymentAdmission.Inspection(active, Map.of(), Map.of(), false));
        when(store.dueTimers(account, rule, epoch, WINDOW_CLOSE, null, 8)).thenReturn(List.of(due));
        when(recovery.restoreWindow(active, store, key, due.pinsDigest(), compiler)).thenReturn(restored);
        when(restored.consumeDiscovered(eq(due), any(UUID.class))).thenReturn(output);
        assertEquals(List.of(output), new WorkerDueWindows(admission, recovery, store, compiler).drain(8, 8));
        verify(store).dueTimers(account, rule, epoch, WINDOW_CLOSE, null, 8);
    }

    @Test void ownerFailureLeavesTimerPending() {
        when(admission.candidates(8, 0)).thenReturn(List.of(active));
        when(admission.inspect(active)).thenThrow(new VclException("AUTOMATION_GRANT_INACTIVE", ""));
        assertTrue(new WorkerDueWindows(admission, recovery, store, compiler).drain(8, 8).isEmpty());
        verifyNoInteractions(store, recovery);
    }

    @Test void advancesPastFailedRecoveryThenWrapsAndRetries() {
        var first = due("b");
        var second = due("c");
        var restored = mock(DurableJournalWindow.class);
        var output = mock(KeyedStateStore.OutputStatus.class);
        when(admission.candidates(8, 0)).thenReturn(List.of(active));
        when(store.dueTimers(account, rule, epoch, WINDOW_CLOSE, null, 1)).thenReturn(List.of(first));
        when(store.dueTimers(account, rule, epoch, WINDOW_CLOSE, first, 1)).thenReturn(List.of(second));
        when(recovery.restoreWindow(active, store, first.key(), first.pinsDigest(), compiler))
                .thenThrow(new VclException("WINDOW_RECIPE_INVALID", ""));
        when(recovery.restoreWindow(active, store, second.key(), second.pinsDigest(), compiler)).thenReturn(restored);
        when(restored.consumeDiscovered(eq(second), any(UUID.class))).thenReturn(output);

        var worker = new WorkerDueWindows(admission, recovery, store, compiler);
        assertTrue(worker.drain(8, 1).isEmpty());
        assertEquals(List.of(output), worker.drain(8, 1));
        assertTrue(worker.drain(8, 1).isEmpty());
        assertTrue(worker.drain(8, 1).isEmpty());
        verify(recovery, times(2)).restoreWindow(active, store, first.key(), first.pinsDigest(), compiler);
    }

    private KeyedStateStore.DueTimer due(String subject) {
        var key = new KeyedStateStore.Key(account, rule, epoch, subject.repeat(64), "a".repeat(64));
        var timer = new KeyedStateStore.Timer(UUID.randomUUID(), WINDOW_CLOSE, Instant.EPOCH,
                KeyedStateStore.State.parse("{}"));
        return new KeyedStateStore.DueTimer(key, "d".repeat(64), new KeyedStateStore.ScheduledTimer(timer, 1));
    }

    @Test void visitsLaterActiveDeploymentsOnTheNextBoundedScan() {
        var later = new JdbcRuleDeployments.Deployment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
                JdbcRuleDeployments.State.ACTIVE, 1);
        when(admission.candidates(1, 0)).thenReturn(List.of(active));
        when(admission.candidates(1, 1)).thenReturn(List.of(later));
        var worker = new WorkerDueWindows(admission, recovery, store, compiler);
        worker.drain(1, 1);
        worker.drain(1, 1);
        verify(admission).inspect(later);
    }
}
