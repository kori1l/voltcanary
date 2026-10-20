package com.voltcanary.simulator.yaml;

import java.util.List;

public record GpsConfig(String routeId, double speedKmh, List<Waypoint> waypoints, boolean vehicleMetrics,
        double initialFuelPercent, double consumptionLitresPer100Km, double fuelTankLitres, double initialOdometerKm) {
    public GpsConfig(String routeId, double speedKmh) { this(routeId, speedKmh, List.of(), false, 80, 8, 50, 0); }
    public record Waypoint(double latitude, double longitude, int stopSeconds) { }
}
