package com.voltcanary.simulator.sender;

import com.voltcanary.simulator.generator.GeneratedTelemetryPoint;

public interface TelemetrySender {
    boolean supports(String protocol);

    void send(GeneratedTelemetryPoint point, String sourceToken);
}
