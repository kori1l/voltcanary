package com.voltcanary.aiagent.model;

import java.util.List;
import java.util.Map;

/** Model-proposed clarification, separate from remembered task values and execution. */
public record PlanningQuestion(
    List<String> missingFields,
    String suggestedReply,
    String suggestionLabel,
    Map<String, Object> suggestedValues,
    @com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT
    )
    boolean manualOnly
) {
    public PlanningQuestion(
        List<String> missingFields,
        String suggestedReply,
        String suggestionLabel,
        Map<String, Object> suggestedValues
    ) {
        this(missingFields, suggestedReply, suggestionLabel, suggestedValues, false);
    }

    @com.fasterxml.jackson.annotation.JsonProperty(
        value = "requiresExplicitChoice",
        access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY
    )
    public boolean requiresExplicitChoice() {
        return (
            manualOnly ||
            (missingFields != null &&
                missingFields
                    .stream()
                    .anyMatch(field -> field.equals("sourceId") || field.equals("sourceRef")))
        );
    }

    public boolean bounded() {
        return (
            missingFields != null &&
            !missingFields.isEmpty() &&
            missingFields.size() <= 12 &&
            missingFields
                .stream()
                .allMatch(field -> field != null && field.matches("[a-zA-Z][a-zA-Z0-9.]{0,79}")) &&
            suggestedReply != null &&
            suggestedReply.length() <= 2000 &&
            suggestionLabel != null &&
            !suggestionLabel.isBlank() &&
            suggestionLabel.length() <= 160 &&
            suggestedValues != null &&
            suggestedValues.size() == missingFields.size() &&
            missingFields
                .stream()
                .allMatch(
                    field ->
                        (suggestedValues.get(field) instanceof String value &&
                            !value.isBlank() &&
                            value.length() <= 1000) ||
                        suggestedValues.get(field) instanceof Number ||
                        suggestedValues.get(field) instanceof Boolean
                )
        );
    }
}
