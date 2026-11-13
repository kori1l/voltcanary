package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voltcanary.aiagent.domain.DashboardTaskState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardTaskStateTest {
    @Test
    void exactRestoreReusesStoredFactsAndSupersedesOnlyLaterWork() {
        var initial = initial();
        UUID oldReview = UUID.randomUUID();
        var snapshot = initial.begin(UUID.randomUUID(), "Создай карту")
                .confirmName("Первое имя", DashboardTaskState.Provenance.USER_EXPLICIT).finish(oldReview, true, null);
        var changed = snapshot.begin(UUID.randomUUID(), "Назови Второе имя")
                .confirmName("Второе имя", DashboardTaskState.Provenance.USER_EXPLICIT).finish(null, false, "PROVIDER_FAILURE");
        var restoring = changed.begin(UUID.randomUUID(), "Верни предыдущий вариант");
        UUID restoredReview = UUID.randomUUID();
        var restored = restoring.restoreTo(snapshot, "Первое имя", snapshot.selection()).finish(restoredReview, true, null);
        assertThat(restored.name()).isEqualTo(snapshot.name());
        assertThat(restored.originalRequest()).isEqualTo(initial.originalRequest());
        assertThat(restored.lastAcceptedReviewId()).isEqualTo(restoredReview);
        assertThat(restored.pending().get(0)).isEqualTo(snapshot.pending().getFirst());
        assertThat(restored.pending().get(1).disposition()).isEqualTo(DashboardTaskState.Disposition.SUPERSEDED);
        assertThat(restored.pending().get(2).disposition()).isEqualTo(DashboardTaskState.Disposition.PENDING);
    }

    @Test
    void evenAnExistingReviewCannotRestoreAnotherTasksFacts() {
        var current = initial().begin(UUID.randomUUID(), "Верни вариант");
        var other = initial();
        assertThatThrownBy(() -> current.restoreTo(other, "Чужое имя", other.selection())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sourceChoicesForDifferentDevicesSurviveFailureAndLegacyJson() throws Exception {
        UUID task = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        var initial = new DashboardTaskState(1, task, task, UUID.randomUUID(), UUID.randomUUID(), 0, "Create", null,
                new DashboardTaskState.Selection(List.of(first, second), null, null, null, null), null, null, null, null, "NEW", null,
                Instant.now().plusSeconds(3600), List.of());
        var failed = initial.begin(UUID.randomUUID(), "Source A").confirmSource("source-A", first).finish(null, false, "PROVIDER_FAILURE");
        var continued = failed.begin(UUID.randomUUID(), "Source B").confirmSource("source-B", second).finish(null, false, "PROVIDER_FAILURE");
        assertThat(continued.selection().sourcesByDevice()).containsEntry(first, "source-A").containsEntry(second, "source-B");
        assertThat(failed.selection().sourcesByDevice()).doesNotContainKey(second);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        assertThat(mapper.readValue(mapper.writeValueAsString(continued), DashboardTaskState.class).selection()).isEqualTo(continued.selection());
        var old = mapper.readValue("{\"deviceIds\":[\"" + first + "\"],\"sourceId\":\"legacy-source\"}", DashboardTaskState.Selection.class);
        assertThat(old.sourcesByDevice()).containsEntry(first, "legacy-source");
    }

    private DashboardTaskState initial() {
        UUID task = UUID.randomUUID();
        return new DashboardTaskState(1, task, task, UUID.randomUUID(), UUID.randomUUID(), 0,
                "Создай карту и скорость", null, new DashboardTaskState.Selection(List.of(), null, null, null, null),
                null, null, null, null, "NEW", null, Instant.now().plusSeconds(3600), List.of());
    }
}
