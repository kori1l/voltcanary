package com.voltcanary.common.events.telemetry;

import java.util.UUID;

public record VariableRuleSelector(
    UUID variableId,
    UUID dataSourceId,
    String unit,
    Integer maxLiveAgeSeconds
) {
    public VariableRuleSelector {
        maxLiveAgeSeconds = maxLiveAgeSeconds == null ? 600 : maxLiveAgeSeconds;
    }
}
