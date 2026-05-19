package com.voltcanary.simulator.dto;

import java.util.List;

public record SimulationRunDetailResponse(SimulationRunResponse run, List<SimulationRunEventResponse> events) {
}
