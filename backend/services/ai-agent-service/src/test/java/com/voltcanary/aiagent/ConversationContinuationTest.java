package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.voltcanary.aiagent.conversation.ConversationContinuation;
import com.voltcanary.aiagent.conversation.ConversationResource;
import com.voltcanary.aiagent.conversation.ConversationStore;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ConversationContinuationTest {

    private final ConversationStore store = mock(ConversationStore.class);
    private final AuthorizedPlanningContext contexts = mock(AuthorizedPlanningContext.class);
    private final ConversationContinuation continuation = new ConversationContinuation(
        store,
        contexts
    );
    private final AuthorizedPlanningContext.Scope owner = new AuthorizedPlanningContext.Scope(
        UUID.randomUUID(),
        UUID.randomUUID()
    );
    private final UUID conversation = UUID.randomUUID();

    @Test
    void bindsMostRecentSuccessfulDeviceAndSourceWithProvenanceBeforePlanning() {
        var recent = resource("bus22", 8);
        when(store.resources(owner, conversation)).thenReturn(
            List.of(recent, resource("bus21", 2))
        );
        var result = resolve("Теперь создай для него дашборд", Map.of());
        assertThat(result.question()).isNull();
        assertThat(result.context().get("deviceIds")).isEqualTo(
            List.of(recent.deviceId().toString())
        );
        assertThat(result.context().get("sourceSelections")).isEqualTo(
            Map.of(recent.deviceId().toString(), recent.sourceId())
        );
        assertThat(
            ((Map<?, ?>) result.context().get("conversationSelection")).get("reviewId")
        ).isEqualTo(recent.reviewId().toString());
        verify(contexts).revalidate(any(), eq("Bearer fixture"));
    }

    @Test
    void explicitSelectionOverridesHistoryAndDiscardsClientProvenance() {
        UUID selected = UUID.randomUUID();
        var result = resolve(
            "Дашборд для этого автобуса",
            Map.of(
                "deviceId",
                selected.toString(),
                "conversationSelection",
                Map.of("origin", "invented")
            )
        );
        assertThat(result.context())
            .containsEntry("deviceId", selected.toString())
            .doesNotContainKey("conversationSelection");
        verifyNoInteractions(store, contexts);
    }

    @Test
    void namedEarlierDeviceOverridesMostRecentAndSameReceiptAmbiguityAsksRealChoices() {
        var recent = resource("bus22", 8);
        var earlier = resource("bus21", 2);
        when(store.resources(owner, conversation)).thenReturn(List.of(recent, earlier));
        assertThat(
            resolve("Создай дашборд для bus21", Map.of()).context().get("deviceIds")
        ).isEqualTo(List.of(earlier.deviceId().toString()));
        when(store.resources(owner, conversation)).thenReturn(
            List.of(recent, resource("bus23", 8))
        );
        assertThat(resolve("Дашборд для этого автобуса", Map.of()).question()).contains(
            "bus22",
            "bus23"
        );
    }

    @Test
    void missingAndDeletedDevicesNeverFallBackToFleetOrAnOlderDevice() {
        when(store.resources(owner, conversation)).thenReturn(List.of());
        assertThat(resolve("Дашборд для этого автобуса", Map.of()).question()).contains(
            "нет подтверждённого результата"
        );
        verifyNoInteractions(contexts);
        when(store.resources(owner, conversation)).thenReturn(
            List.of(resource("bus22", 8), resource("bus21", 2))
        );
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
            .when(contexts)
            .revalidate(any(), any());
        assertThatThrownBy(() -> resolve("Дашборд для него", Map.of())).isInstanceOf(
            ResponseStatusException.class
        );
        verify(contexts, times(1)).revalidate(any(), any());
    }

    private ConversationContinuation.Resolution resolve(String message, Map<String, Object> input) {
        return continuation.resolve(owner, conversation, message, input, "Bearer fixture");
    }

    private ConversationResource resource(String name, long revision) {
        return new ConversationResource(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            name,
            UUID.randomUUID(),
            UUID.randomUUID(),
            revision
        );
    }
}
