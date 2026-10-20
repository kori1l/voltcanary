package com.voltcanary.device.variable.domain;

import java.util.Map;
import java.util.UUID;

public record VariableBinding(UUID dataSourceId, UUID variableId, int revision, int version,
        Map<String, String> inputPaths, boolean enabled, boolean inherited) { }
