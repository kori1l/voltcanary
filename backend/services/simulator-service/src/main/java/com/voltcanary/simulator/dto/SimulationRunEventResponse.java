package com.voltcanary.simulator.dto;

import java.time.Instant;
import java.util.UUID;

public record SimulationRunEventResponse(UUID id, String eventType, String message, Instant createdAt) {
}
