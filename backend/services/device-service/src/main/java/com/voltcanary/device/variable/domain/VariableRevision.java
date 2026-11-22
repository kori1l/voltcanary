package com.voltcanary.device.variable.domain;

import com.voltcanary.common.events.telemetry.VariableContract;

public record VariableRevision(
    int revision,
    String state,
    String digest,
    VariableContract contract
) {}
