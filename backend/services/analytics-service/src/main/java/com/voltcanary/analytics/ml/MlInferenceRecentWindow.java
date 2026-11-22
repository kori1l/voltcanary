package com.voltcanary.analytics.ml;

import lombok.Builder;

/** Typed inference context shared by online prediction and training smoke inference. */
@Builder
public record MlInferenceRecentWindow(
    Integer sampleCount,
    Long excludedSampleCount,
    String windowStart,
    String windowEnd
) {}
