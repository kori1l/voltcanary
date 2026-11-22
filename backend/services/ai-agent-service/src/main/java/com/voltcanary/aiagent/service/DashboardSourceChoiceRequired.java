package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.PlanningQuestion;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A genuine source choice is user input, not a model schema-repair failure. */
final class DashboardSourceChoiceRequired extends RuntimeException {

    private final List<String> labels;
    private final List<String> descriptions;

    DashboardSourceChoiceRequired(
        Map<String, Object> choice,
        AuthorizedPlanningContext.Prepared context
    ) {
        super("An explicit recorded source choice is required");
        var options = new java.util.LinkedHashMap<String, String>();
        context
            .refs()
            .stream()
            .filter(ref -> ref.kind().equals("dashboard-data"))
            .map(AuthorizedPlanningContext.ResourceRef::summary)
            .filter(
                candidate ->
                    Objects.equals(choice.get("deviceId"), candidate.get("deviceId")) &&
                    Objects.equals(choice.get("family"), candidate.get("family")) &&
                    (Objects.equals(choice.get("key"), candidate.get("key")) ||
                        Objects.equals(choice.get("label"), candidate.get("label")))
            )
            .filter(candidate -> candidate.get("externalSourceId") instanceof String)
            .forEach(candidate ->
                options.put(
                    String.valueOf(
                        candidate.getOrDefault(
                            "sourceChoiceLabel",
                            candidate.get("externalSourceId")
                        )
                    ),
                    String.valueOf(candidate.get("externalSourceId"))
                )
            );
        labels = List.copyOf(options.keySet());
        descriptions = options
            .entrySet()
            .stream()
            .map(option ->
                option.getKey().equals(option.getValue())
                    ? option.getValue()
                    : option.getKey() + " (" + option.getValue() + ")"
            )
            .toList();
        if (labels.size() < 2) throw new DashboardPreparationRejected(
            "Source ambiguity metadata is unavailable; refresh the data choices"
        );
    }

    AgentPlan question(AgentPlan plan) {
        String options = descriptions
            .stream()
            .map(label -> "«" + label + "»")
            .collect(java.util.stream.Collectors.joining(", "));
        var question = new PlanningQuestion(
            List.of("sourceId"),
            labels.getFirst(),
            "Выберите источник данных",
            Map.of("sourceId", labels.getFirst())
        );
        return new AgentPlan(
            plan.id(),
            plan.userId(),
            plan.userMessage(),
            "Выберите источник данных",
            "У транспорта несколько подходящих источников: " +
                options +
                ". Какой использовать? Ответьте названием источника.",
            plan.safetyNotes(),
            List.of(),
            plan.createdAt(),
            plan.providerType(),
            plan.providerName(),
            plan.modelId(),
            plan.providerId(),
            plan.modelConfigId(),
            plan.fallbackUsed(),
            question
        );
    }

    static AgentPlan availableOptions(AgentPlan plan, AuthorizedPlanningContext.Prepared context) {
        if (
            !plan.actions().isEmpty() ||
            (plan.clarification() != null && !plan.clarification().requiresExplicitChoice()) ||
            !(context.context().get("name") instanceof String) ||
            plan.summary().equals("Не удалось подготовить изменения") ||
            plan
                .safetyNotes()
                .stream()
                .anyMatch(note -> note.contains("Provider request failed"))
        ) return plan;
        var options = new java.util.LinkedHashMap<String, String>();
        context
            .refs()
            .stream()
            .filter(
                ref ->
                    ref.kind().equals("dashboard-data") &&
                    Boolean.TRUE.equals(ref.summary().get("requiresSourceChoice"))
            )
            .map(AuthorizedPlanningContext.ResourceRef::summary)
            .filter(choice -> choice.get("sourceChoiceLabel") instanceof String)
            .forEach(choice ->
                options.put(
                    String.valueOf(choice.get("sourceChoiceLabel")),
                    String.valueOf(choice.get("externalSourceId"))
                )
            );
        if (options.size() < 2) return plan;
        String offered = options
            .entrySet()
            .stream()
            .map(option -> option.getKey() + " — " + option.getValue())
            .collect(java.util.stream.Collectors.joining("; "));
        return new AgentPlan(
            plan.id(),
            plan.userId(),
            plan.userMessage(),
            plan.summary(),
            plan.explanation() +
                "\n\nДоступные источники: " +
                offered +
                ". Выбор можно указать коротким названием источника.",
            plan.safetyNotes(),
            plan.actions(),
            plan.createdAt(),
            plan.providerType(),
            plan.providerName(),
            plan.modelId(),
            plan.providerId(),
            plan.modelConfigId(),
            plan.fallbackUsed(),
            plan.clarification()
        );
    }
}
