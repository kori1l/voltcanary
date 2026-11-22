package com.voltcanary.common.web;

import java.util.List;
import java.util.UUID;

public record VehiclePositionTargets(UUID userId, List<Target> targets) {
    public record Target(UUID deviceId, String sourceId) {}
}
