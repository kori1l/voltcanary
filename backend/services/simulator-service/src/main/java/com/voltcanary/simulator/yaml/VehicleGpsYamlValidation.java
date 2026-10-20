package com.voltcanary.simulator.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class VehicleGpsYamlValidation {
    private static final Set<String> METRICS = Set.of("latitude", "longitude", "speedKmh", "headingDeg");
    private VehicleGpsYamlValidation() { }

    public static void validate(JsonNode root, SimulationYamlParser parser, List<ValidationError> errors) {
        JsonNode gps = root.path("gps");
        Set<String> fields = Set.of("routeId", "speedKmh", "waypoints", "vehicleMetrics", "initialFuelPercent", "consumptionLitresPer100Km", "fuelTankLitres", "initialOdometerKm");
        require(errors, "gps", gps.isObject() && gps.has("routeId") && gps.has("speedKmh"), "GPS requires routeId and speedKmh");
        gps.fieldNames().forEachRemaining(name -> require(errors, "gps." + name, fields.contains(name), "Unsupported GPS field"));
        require(errors, "gps.vehicleMetrics", !gps.has("vehicleMetrics") || gps.path("vehicleMetrics").isBoolean(), "Must be boolean");
        numeric(gps, errors, "initialFuelPercent", 0, 100);
        numeric(gps, errors, "consumptionLitresPer100Km", 0, 100);
        numeric(gps, errors, "fuelTankLitres", 1, 1000);
        numeric(gps, errors, "initialOdometerKm", 0, 10000000);
        if (gps.has("waypoints")) {
            JsonNode points = gps.path("waypoints");
            require(errors, "gps.waypoints", points.isArray() && points.size() >= 2 && points.size() <= 100, "Route requires 2–100 waypoints");
            java.util.Set<String> distinct = new java.util.HashSet<>();
            for (JsonNode point : points) {
                require(errors, "gps.waypoints", point.isObject() && point.size() >= 2 && point.size() <= 3 && point.has("latitude") && point.has("longitude")
                        && point.properties().stream().allMatch(entry -> Set.of("latitude", "longitude", "stopSeconds").contains(entry.getKey())), "Waypoint accepts latitude, longitude, stopSeconds");
                require(errors, "gps.waypoints.latitude", bounded(point.path("latitude"), -90, 90), "Invalid latitude");
                require(errors, "gps.waypoints.longitude", bounded(point.path("longitude"), -180, 180), "Invalid longitude");
                require(errors, "gps.waypoints.stopSeconds", !point.has("stopSeconds") || (point.path("stopSeconds").isIntegralNumber() && bounded(point.path("stopSeconds"), 0, 3600)), "Stop requires integer 0–3600 seconds");
                distinct.add(point.path("latitude").asDouble() + ":" + point.path("longitude").asDouble());
            }
            require(errors, "gps.waypoints", distinct.size() >= 2, "Route must have nonzero length");
        }
        require(errors, "gps.routeId", root.path("gps").path("routeId").asText().equals("CITY_LOOP"), "Only CITY_LOOP is supported");
        JsonNode speed = root.path("gps").path("speedKmh");
        require(errors, "gps.speedKmh", speed.isNumber() && Double.isFinite(speed.asDouble()) && speed.asDouble() >= 1 && speed.asDouble() <= 120, "Speed must be 1–120 km/h");
        require(errors, "simulator.mode", root.path("simulator").path("mode").asText("NORMAL").equals("NORMAL"), "Only NORMAL is supported");
        require(errors, "simulator.protocol", root.path("simulator").path("protocol").asText().equals("HTTP"), "Vehicle GPS uses HTTP");
        JsonNode schedule = root.path("schedule");
        require(errors, "schedule.speed", schedule.path("speed").isMissingNode() || (schedule.path("speed").isNumber() && schedule.path("speed").asDouble() == 1), "Simulation speed must be 1");
        try {
            Duration interval = parser.parseDuration(schedule.path("interval").asText("5s"));
            Duration duration = parser.parseDuration(schedule.path("duration").asText("0s"));
            require(errors, "schedule.interval", interval.getNano() == 0 && interval.getSeconds() >= 1 && interval.getSeconds() <= 30, "Interval must be integer 1–30 seconds");
            require(errors, "schedule.duration", duration.compareTo(interval) >= 0 && duration.compareTo(Duration.ofHours(24)) <= 0, "Duration must be between interval and 24 hours");
            require(errors, "schedule.startDelay", parser.parseDuration(schedule.path("startDelay").asText("0s")).isZero(), "Start delay must be zero");
        } catch (RuntimeException exception) {
            errors.add(new ValidationError("schedule", "Invalid vehicle duration"));
        }
        JsonNode anomalies = root.path("anomalies");
        require(errors, "anomalies", anomalies.isMissingNode() || (anomalies.isArray() && anomalies.isEmpty()), "Vehicle GPS does not support anomalies");
        JsonNode metrics = root.path("metrics");
        java.util.Set<String> names = new java.util.HashSet<>(METRICS);
        if (gps.path("vehicleMetrics").asBoolean(false)) names.addAll(Set.of("ignition", "fuelPercent", "odometerKm"));
        require(errors, "metrics", metrics.isObject() && metrics.size() == names.size(), "Generated metric set must match GPS options");
        for (String name : names) {
            JsonNode metric = metrics.path(name);
            require(errors, "metrics." + name, metric.isObject() && metric.size() == 1 && metric.path("type").asText().equals("generated"), "GPS metrics require only type: generated");
        }
    }

    private static boolean bounded(JsonNode value, double min, double max) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= min && value.asDouble() <= max;
    }
    private static void numeric(JsonNode gps, List<ValidationError> errors, String name, double min, double max) {
        require(errors, "gps." + name, !gps.has(name) || bounded(gps.path(name), min, max), "Value outside supported range");
    }

    private static void require(List<ValidationError> errors, String path, boolean valid, String message) {
        if (!valid) {
            errors.add(new ValidationError(path, message));
        }
    }
}
