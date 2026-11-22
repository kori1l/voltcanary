package com.voltcanary.aiagent.vehicle;

import java.util.Map;

/** Explicit user-selected map centre, never inferred telemetry or a location lookup. */
public record VehicleMonitoringPoint(double latitude, double longitude) {
    public VehicleMonitoringPoint {
        if (
            !Double.isFinite(latitude) ||
            Math.abs(latitude) > 90 ||
            !Double.isFinite(longitude) ||
            Math.abs(longitude) > 180
        ) {
            throw new IllegalArgumentException("Invalid selected monitoring centre");
        }
    }

    public static String selection(Object value) {
        if (
            !(value instanceof Map<?, ?> point) ||
            point.size() != 2 ||
            !(point.get("latitude") instanceof Number latitude) ||
            !(point.get("longitude") instanceof Number longitude)
        ) throw new IllegalArgumentException("Expected an explicit numeric map centre");
        var checked = new VehicleMonitoringPoint(latitude.doubleValue(), longitude.doubleValue());
        return checked.latitude() + "," + checked.longitude();
    }

    public static VehicleMonitoringPoint parse(String value) {
        if (value.length() > 100) throw new IllegalArgumentException(
            "Invalid selected monitoring centre"
        );
        var coordinates = value.split(",", -1);
        if (coordinates.length != 2) throw new IllegalArgumentException(
            "Invalid selected monitoring centre"
        );
        return new VehicleMonitoringPoint(
            Double.parseDouble(coordinates[0]),
            Double.parseDouble(coordinates[1])
        );
    }
}
