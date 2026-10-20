package com.voltcanary.common.events.analytics;

import java.util.UUID;

public record VariableRuleEvidence(UUID variableId, int variableRevision, UUID dataSourceId,
        UUID sourceEventId, UUID lineageId, int ruleVersion, double value, String unit,
        int maxLiveAgeSeconds, int cooldownSeconds, int deduplicationWindowSeconds) { }
