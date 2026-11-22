package com.voltcanary.telemetry.vehicle;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record VehicleMonitoringSettings(double speedLimitKmh, int idleMinutes, List<Zone> zones) {
    public record Zone(
        UUID id,
        String name,
        double latitude,
        double longitude,
        double radiusMetres
    ) {}

    public static VehicleMonitoringSettings defaults() {
        return new VehicleMonitoringSettings(80, 2, List.of());
    }

    public void validate() {
        boolean valid =
            Double.isFinite(speedLimitKmh) &&
            speedLimitKmh >= 1 &&
            speedLimitKmh <= 300 &&
            idleMinutes >= 1 &&
            idleMinutes <= 60 &&
            zones != null &&
            zones.size() <= 50;
        var ids = new HashSet<UUID>();
        if (valid) for (Zone zone : zones) {
            valid =
                zone != null &&
                zone.id() != null &&
                ids.add(zone.id()) &&
                zone.name() != null &&
                !zone.name().isBlank() &&
                zone.name().length() <= 100 &&
                Double.isFinite(zone.latitude()) &&
                Math.abs(zone.latitude()) <= 90 &&
                Double.isFinite(zone.longitude()) &&
                Math.abs(zone.longitude()) <= 180 &&
                Double.isFinite(zone.radiusMetres()) &&
                zone.radiusMetres() >= 10 &&
                zone.radiusMetres() <= 50000;
            if (!valid) break;
        }
        if (!valid) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid monitoring settings or zones"
        );
    }
}
