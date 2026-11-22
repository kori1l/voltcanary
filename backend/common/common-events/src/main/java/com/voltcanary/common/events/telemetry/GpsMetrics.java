package com.voltcanary.common.events.telemetry;

import java.util.Map;

/** Geographic envelope invariants shared by ingress and persisted telemetry. */
public final class GpsMetrics {

    private GpsMetrics() {}

    public static boolean hasPosition(Map<String, Object> metrics) {
        return (
            metrics != null && (metrics.containsKey("latitude") || metrics.containsKey("longitude"))
        );
    }

    public static boolean valid(Map<String, Object> metrics) {
        if (
            !TransportContext.validMetrics(metrics) || !ConfiguredFields.valid(metrics)
        ) return false;
        if (!hasPosition(metrics)) {
            return true;
        }
        return (
            within(metrics.get("latitude"), -90, 90, false) &&
            within(metrics.get("longitude"), -180, 180, false) &&
            (!metrics.containsKey("speedKmh") || within(metrics.get("speedKmh"), 0, 300, false)) &&
            (!metrics.containsKey("headingDeg") ||
                within(metrics.get("headingDeg"), 0, 360, true)) &&
            (!metrics.containsKey("ignition") ||
                (within(metrics.get("ignition"), 0, 1, false) &&
                    ((Number) metrics.get("ignition")).doubleValue() % 1 == 0)) &&
            (!metrics.containsKey("fuelPercent") ||
                within(metrics.get("fuelPercent"), 0, 100, false)) &&
            (!metrics.containsKey("odometerKm") ||
                within(metrics.get("odometerKm"), 0, 10000000, false))
        );
    }

    private static boolean within(Object value, double min, double max, boolean exclusiveMax) {
        return (
            value instanceof Number number &&
            Double.isFinite(number.doubleValue()) &&
            number.doubleValue() >= min &&
            (exclusiveMax ? number.doubleValue() < max : number.doubleValue() <= max)
        );
    }
}
