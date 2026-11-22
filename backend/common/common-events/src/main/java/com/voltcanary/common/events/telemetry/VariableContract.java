package com.voltcanary.common.events.telemetry;

import java.util.Map;
import java.util.UUID;

/** Immutable published wire definition; no executable code or source credentials. */
public record VariableContract(
    UUID variableId,
    int revision,
    String key,
    String label,
    String origin,
    String unit,
    String languageVersion,
    Map<String, Input> inputs,
    VariableExpression expression,
    Double minimum,
    Double maximum
) {
    public VariableContract {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public record Input(String unit, Double minimum, Double maximum) {}
}
