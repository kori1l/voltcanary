package com.voltcanary.device.variable.domain;

import java.util.UUID;

public record VariableDefinition(
    UUID id,
    UUID accountId,
    String deviceType,
    String key,
    String label,
    String origin,
    String unit,
    int version,
    boolean archived,
    Integer defaultRevision
) {}
