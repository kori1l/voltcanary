package com.voltcanary.analytics.dto;

import java.time.Instant;

public record MlDatasetWindowDto(
        Instant from,
        Instant to,
        MlCandidateWindowKind source) {
}
