package com.voltcanary.analytics.ml;

import lombok.Builder;

/** Typed inference context shared by online prediction and training smoke inference. */
@Builder
public record MlInferenceBaseline(
    String metric,
    Integer sampleCount,
    Double mean,
    Double median,
    Double p90,
    Double min,
    Double max
) {}
