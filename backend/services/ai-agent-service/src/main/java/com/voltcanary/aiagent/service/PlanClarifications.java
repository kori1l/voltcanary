package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext.Prepared;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Keeps user replies separate from execution and reloads selections through current APIs. */
@Component
@RequiredArgsConstructor
public class PlanClarifications {

    public record Continuation(
        PlanStore.Stored parent,
        String message,
        Map<String, Object> input,
        int round,
        List<String> answers
    ) {}

    private static final String STATE = "_clarification";
    private static final Set<String> INPUT_FIELDS = Set.of(
        "name",
        "type",
        "manufacturer",
        "model",
        "profile",
        "protocol",
        "preset",
        "sourceType",
        "externalId",
        "sourceId",
        "metric",
        "from",
        "to",
        "yamlConfig",
        "startImmediately",
        "deviceId",
        "deviceIds",
        "sourceSelections",
        "locationId",
        "alertId",
        "previousDashboardIntent",
        "originalDashboardRequest",
        "pendingDashboardChange",
        "originalDeviceRequest"
    );
    private final PlanStore store;
    private final AuthorizedPlanningContext contexts;
    private final PlanningInputGuard guard;

    public Continuation prepare(
        AuthenticatedUser user,
        UUID parentId,
        String answer,
        String authorization
    ) {
        var scope = contexts.scope(user, authorization);
        var saved = store.find(parentId, scope);
        if (
            "EXPIRED".equals(saved.status()) || !saved.expiresAt().isAfter(java.time.Instant.now())
        ) {
            throw new ResponseStatusException(HttpStatus.GONE, "Plan expired; prepare again");
        }
        if (!List.of("NEEDS_INPUT", "READY").contains(saved.status())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only unexecuted plans can be clarified"
            );
        }
        if (answer == null || answer.isBlank() || answer.length() > 2000) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Clarification must contain 1–2000 characters"
            );
        }
        Map<String, Object> storedContext = store.context(parentId, scope);
        Map<?, ?> state = storedContext.get(STATE) instanceof Map<?, ?> value ? value : Map.of();
        int round = state.get("round") instanceof Number value ? value.intValue() + 1 : 1;
        if (round > 30) throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Start a new request after thirty clarifications"
        );
        String originalRequest =
            state.get("input") instanceof Map<?, ?> values &&
            values.get("originalDashboardRequest") instanceof String original
                ? original
                : saved.plan().userMessage();
        String message =
            originalRequest +
            "\n\nОтвет пользователя на предыдущий вопрос ассистента:\n«" +
            answer.trim() +
            "»";
        var answers = new java.util.ArrayList<String>();
        if (state.get("answers") instanceof List<?> previous) {
            for (Object value : previous) if (value instanceof String text) answers.add(text);
        }
        answers.add(answer.trim());
        var input = new LinkedHashMap<>(
            state.get("input") instanceof Map<?, ?> original
                ? supportedInput(original)
                : legacyInput(storedContext, saved)
        );
        guard.validate(message, input);
        // Previous model output is intent context only; never copy old opaque refs into commands.
        String question = saved.plan().explanation();
        if (question != null) for (var ref : saved.refs()) {
            String label = String.valueOf(
                ref
                    .summary()
                    .getOrDefault(
                        "name",
                        ref.summary().getOrDefault("displayName", "предыдущий выбор")
                    )
            );
            question = question.replace(ref.ref(), label);
            if (List.of("device", "location", "alert").contains(ref.kind())) question =
                question.replace(ref.resourceId(), label);
        }
        if (question != null && !question.isBlank()) input.put("priorAssistantQuestion", question);
        if (
            input.containsKey("originalDeviceRequest") &&
            saved.plan().clarification() != null &&
            List.of("name").equals(saved.plan().clarification().missingFields())
        ) input.put("devicePendingField", "name");
        if (
            saved.plan().clarification() != null && saved.plan().clarification().bounded()
        ) input.put("priorSuggestedValues", saved.plan().clarification().suggestedValues());
        boolean ambiguousSources =
            storedContext.get("name") instanceof String knownName &&
            !knownName.isBlank() &&
            storedContext.get("dashboardDataDiagnostics") instanceof List<?> diagnostics &&
            diagnostics
                .stream()
                .anyMatch(
                    item ->
                        item instanceof Map<?, ?> diagnostic &&
                        "AMBIGUOUS_SOURCE".equals(diagnostic.get("code"))
                );
        if (
            (saved.plan().clarification() != null &&
                saved.plan().clarification().requiresExplicitChoice()) ||
            (saved.plan().clarification() == null &&
                saved.plan().actions().isEmpty() &&
                ambiguousSources)
        ) {
            List<Object> sources = List.of();
            for (String field : List.of("sourceChoiceLabel", "sourceId", "externalSourceId")) {
                sources = saved
                    .refs()
                    .stream()
                    .filter(ref -> ref.kind().equals("dashboard-data"))
                    .filter(ref -> answer.strip().equals(ref.summary().get(field)))
                    .map(ref -> ref.summary().get("sourceId"))
                    .filter(String.class::isInstance)
                    .distinct()
                    .toList();
                if (!sources.isEmpty()) break;
            }
            if (sources.size() == 1) {
                Object chosen = sources.getFirst();
                input.put("sourceId", chosen);
                input.put("sourceSelectionReply", true);
                saved
                    .refs()
                    .stream()
                    .filter(
                        ref ->
                            ref.kind().equals("dashboard-data") &&
                            chosen.equals(ref.summary().get("sourceId"))
                    )
                    .map(ref -> ref.summary().get("deviceId"))
                    .filter(String.class::isInstance)
                    .findFirst()
                    .ifPresent(device -> input.put("sourceDeviceId", device));
            }
        }
        saved
            .plan()
            .actions()
            .stream()
            .filter(
                action -> action.tool() == com.voltcanary.aiagent.model.AgentTool.CREATE_DASHBOARD
            )
            .findFirst()
            .ifPresent(action -> {
                if (action.parameters().get("prepareIntent") instanceof Map<?, ?> intent) {
                    var previous = new LinkedHashMap<String, Object>();
                    for (String key : List.of(
                        "name",
                        "period",
                        "panelLayout",
                        "metricKeys",
                        "widgets"
                    )) {
                        if (intent.get(key) != null) previous.put(key, intent.get(key));
                    }
                    if (action.parameters().get("templateId") != null) previous.put(
                        "templateId",
                        action.parameters().get("templateId")
                    );
                    if (action.parameters().get("templateVersion") != null) previous.put(
                        "templateVersion",
                        action.parameters().get("templateVersion")
                    );
                    if (!previous.containsKey("widgets")) previous.put(
                        "widgets",
                        compositionHint(action.parameters())
                    );
                    input.put("previousDashboardIntent", previous);
                    if (intent.get("name") != null) input.put("name", intent.get("name"));
                    if (intent.get("deviceIds") != null) input.put(
                        "deviceIds",
                        intent.get("deviceIds")
                    );
                }
            });
        var previousReviews = new DashboardReviewHistory(store, contexts).previousIds(saved);
        input.put(
            "dashboardReviewVersions",
            java.util.stream.IntStream.range(0, previousReviews.size())
                .mapToObj(index -> Map.of("restoreSteps", previousReviews.size() - index))
                .toList()
        );
        input.put("clarificationAnswers", List.copyOf(answers));
        guard.inspect(input);
        return new Continuation(saved, message, Map.copyOf(input), round, List.copyOf(answers));
    }

    public Prepared remember(
        Prepared prepared,
        Map<String, Object> input,
        int round,
        UUID parentId,
        List<String> answers
    ) {
        Map<String, Object> context = new LinkedHashMap<>(prepared.context());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("input", supportedInput(input == null ? Map.of() : input));
        state.put("round", round);
        state.put("answers", List.copyOf(answers));
        if (parentId != null) state.put("parentPlanId", parentId.toString());
        context.put(STATE, Map.copyOf(state));
        return new Prepared(prepared.scope(), Map.copyOf(context), prepared.refs());
    }

    public Prepared remember(
        Prepared prepared,
        com.voltcanary.aiagent.model.AgentPlan plan,
        Map<String, Object> input,
        int round,
        UUID parentId,
        List<String> answers
    ) {
        var working = supportedInput(input == null ? Map.of() : input);
        working.putIfAbsent("originalDashboardRequest", plan.userMessage());
        if (prepared.context().get("originalDeviceRequest") instanceof String original) working.put(
            "originalDeviceRequest",
            original
        );
        if (prepared.context().get("name") instanceof String name && !name.isBlank()) working.put(
            "name",
            name
        );
        if (prepared.context().containsKey("originalDeviceRequest")) {
            for (var action : plan.actions())
                if (action.tool() == com.voltcanary.aiagent.model.AgentTool.CREATE_DEVICE) {
                    for (String field : List.of("name", "type", "manufacturer", "model")) {
                        if (action.parameters().get(field) != null) working.put(
                            field,
                            action.parameters().get(field)
                        );
                    }
                    if (action.parameters().get("locationId") != null) working.put(
                        "locationId",
                        action.parameters().get("locationId")
                    );
                }
        }
        if (prepared.context().get("pendingDashboardChange") != null) working.put(
            "pendingDashboardChange",
            prepared.context().get("pendingDashboardChange")
        );
        for (var action : plan.actions()) {
            if (action.parameters().get("prepareIntent") instanceof Map<?, ?> intent) {
                working.remove("pendingDashboardChange");
                working.put("previousDashboardIntent", intent);
                for (String field : List.of("name", "deviceIds"))
                    if (intent.get(field) != null) working.put(field, intent.get(field));
            }
        }
        return remember(prepared, working, round, parentId, answers);
    }

    private Map<String, Object> legacyInput(Map<String, Object> context, PlanStore.Stored saved) {
        Map<String, Object> input = supportedInput(context);
        if (
            context.get("selectedDashboardDeviceRefs") instanceof List<?> selected &&
            !selected.isEmpty()
        ) {
            var ids = saved
                .refs()
                .stream()
                .filter(ref -> ref.kind().equals("device") && selected.contains(ref.ref()))
                .map(AuthorizedPlanningContext.ResourceRef::resourceId)
                .distinct()
                .toList();
            if (ids.size() != selected.size()) throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Recover the equipment selection in a new request"
            );
            input.put("deviceIds", ids);
        }
        return input;
    }

    private Map<String, Object> supportedInput(Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : INPUT_FIELDS) if (input.get(key) != null) result.put(key, input.get(key));
        return result;
    }

    private List<Map<String, Object>> compositionHint(Map<String, Object> parameters) {
        if (
            !(parameters.get("definition") instanceof Map<?, ?> definition) ||
            !(definition.get("widgets") instanceof List<?> widgets)
        ) return List.of();
        List<?> layout =
            definition.get("layouts") instanceof Map<?, ?> layouts &&
            layouts.get("desktop") instanceof List<?> positions
                ? positions
                : List.of();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : widgets)
            if (item instanceof Map<?, ?> widget) {
                var hint = new LinkedHashMap<String, Object>();
                hint.put("key", widget.get("id"));
                hint.put("type", widget.get("type"));
                hint.put("title", widget.get("title"));
                if (
                    widget.get("binding") instanceof Map<?, ?> binding &&
                    binding.get("deviceIds") != null
                ) hint.put("deviceIds", binding.get("deviceIds"));
                if (widget.get("query") instanceof Map<?, ?> query) for (String key : List.of(
                    "metric",
                    "unit",
                    "reducer"
                )) {
                    if (query.get(key) != null) hint.put(key, query.get(key));
                }
                for (Object value : layout)
                    if (
                        value instanceof Map<?, ?> position &&
                        widget.get("id").equals(position.get("widgetId"))
                    ) {
                        int width = ((Number) position.get("w")).intValue();
                        hint.put("width", width >= 12 ? "FULL" : width >= 6 ? "HALF" : "THIRD");
                        hint.put("height", position.get("h"));
                    }
                result.add(hint);
            }
        return result;
    }
}
