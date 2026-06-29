package com.voltcanary.alert.dto;

import java.time.Instant;
import java.util.UUID;

public record AlertRuleTestRequest(
        UUID deviceId,
        CreateAlertRuleRequest rule,
        Instant from,
        Instant to) {
}
