package com.voltcanary.analytics.baseline;

public record BaselineStats(
        double mean,
        double median,
        double p90,
        double min,
        double max,
        int sampleCount) {
}
