package com.voltcanary.aiagent.conversation;

import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** Ground new dashboard tasks before catalogue/model work; never reuse device task intent. */
@RequiredArgsConstructor
public final class ConversationContinuation {

    public record Resolution(Map<String, Object> context, String question) {}

    private final ConversationStore store;
    private final AuthorizedPlanningContext contexts;

    public Resolution resolve(
        AuthorizedPlanningContext.Scope scope,
        UUID conversationId,
        String message,
        Map<String, Object> input,
        String authorization
    ) {
        var values = new LinkedHashMap<String, Object>(input == null ? Map.of() : input);
        if (
            !com.voltcanary.aiagent.service.DashboardPlanningContext.recognizesDashboard(message)
        ) return new Resolution(Map.copyOf(values), null);
        // Server provenance is never accepted from the browser.
        values.remove("conversationSelection");
        if (
            values.get("deviceId") != null ||
            (values.get("deviceIds") instanceof List<?> ids && !ids.isEmpty())
        ) {
            AssistantTrace.event(
                "REFERENT",
                "SUCCEEDED",
                "USER_SELECTION",
                Map.of("origin", "USER_EXPLICIT")
            );
            return new Resolution(Map.copyOf(values), null);
        }
        boolean referring = message.matches(
            "(?isu).*(для него|для неё|для нее|этот|этого|этой|это устройство|this (bus|device)|for (it|him|her)).*"
        );
        var resources = store.resources(scope, conversationId);
        String lower = message.toLowerCase(Locale.ROOT);
        var named = resources
            .stream()
            .filter(resource -> lower.contains(resource.name().toLowerCase(Locale.ROOT)))
            .collect(
                java.util.stream.Collectors.toMap(
                    ConversationResource::deviceId,
                    value -> value,
                    (first, next) -> first,
                    LinkedHashMap::new
                )
            );
        List<ConversationResource> candidates;
        if (!named.isEmpty()) candidates = List.copyOf(named.values());
        else if (referring && !resources.isEmpty()) {
            long latest = resources.getFirst().messageRevision();
            candidates = resources
                .stream()
                .filter(resource -> resource.messageRevision() == latest)
                .toList();
        } else if (!referring) return new Resolution(Map.copyOf(values), null);
        else candidates = List.of();
        if (candidates.size() != 1) {
            AssistantTrace.event(
                "REFERENT",
                "NEEDS_INPUT",
                "DEVICE_SELECTION_REQUIRED",
                Map.of("choices", candidates)
            );
            return new Resolution(
                Map.copyOf(values),
                candidates.isEmpty()
                    ? "Для какого устройства создать дашборд? Выберите устройство: в этом разговоре нет подтверждённого результата его создания."
                    : "Для какого устройства создать дашборд: " +
                          String.join(
                              ", ",
                              candidates.stream().map(ConversationResource::name).toList()
                          ) +
                          "?"
            );
        }
        var resource = candidates.getFirst();
        // Deleted/inaccessible devices fail here; never fall back to an older device or fleet catalogue.
        contexts.revalidate(
            List.of(
                new AuthorizedPlanningContext.ResourceRef(
                    "conversation-device",
                    "device",
                    resource.deviceId().toString(),
                    Map.of()
                )
            ),
            authorization
        );
        values.put("deviceIds", List.of(resource.deviceId().toString()));
        if (resource.sourceId() != null) values.put(
            "sourceSelections",
            Map.of(resource.deviceId().toString(), resource.sourceId())
        );
        values.put(
            "conversationSelection",
            Map.of(
                "origin",
                "CONFIRMED_EXECUTION",
                "reviewId",
                resource.reviewId().toString(),
                "actionId",
                resource.actionId().toString(),
                "conversationId",
                conversationId.toString()
            )
        );
        AssistantTrace.event(
            "REFERENT",
            "SUCCEEDED",
            "CONFIRMED_EXECUTION",
            Map.of("resource", resource)
        );
        return new Resolution(Map.copyOf(values), null);
    }
}
