package com.voltcanary.telemetry.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PositionEvidence {

    private PositionEvidence() {}

    public record Point(
        Instant recordedAt,
        Instant receivedAt,
        double latitude,
        double longitude,
        Double speedKmh,
        Double headingDeg,
        List<String> quality,
        Double ignition,
        Double fuelPercent,
        Double odometerKm,
        com.voltcanary.common.events.telemetry.TransportContext transport,
        java.util.Map<String, java.util.Map<String, Object>> fields
    ) {
        public Point(
            Instant recordedAt,
            Instant receivedAt,
            double latitude,
            double longitude,
            Double speedKmh,
            Double headingDeg,
            List<String> quality,
            Double ignition,
            Double fuelPercent,
            Double odometerKm,
            com.voltcanary.common.events.telemetry.TransportContext transport
        ) {
            this(
                recordedAt,
                receivedAt,
                latitude,
                longitude,
                speedKmh,
                headingDeg,
                quality,
                ignition,
                fuelPercent,
                odometerKm,
                transport,
                java.util.Map.of()
            );
        }

        public Point(
            Instant recordedAt,
            Instant receivedAt,
            double latitude,
            double longitude,
            Double speedKmh,
            Double headingDeg,
            List<String> quality,
            Double ignition,
            Double fuelPercent,
            Double odometerKm
        ) {
            this(
                recordedAt,
                receivedAt,
                latitude,
                longitude,
                speedKmh,
                headingDeg,
                quality,
                ignition,
                fuelPercent,
                odometerKm,
                null
            );
        }

        public Point(
            Instant recordedAt,
            Instant receivedAt,
            double latitude,
            double longitude,
            Double speedKmh,
            Double headingDeg,
            List<String> quality
        ) {
            this(
                recordedAt,
                receivedAt,
                latitude,
                longitude,
                speedKmh,
                headingDeg,
                quality,
                null,
                null,
                null
            );
        }
    }

    public record Recording(List<Point> points, Instant nextCursor, boolean hasMore) {}

    public record Latest(
        UUID deviceId,
        String sourceId,
        Instant asOf,
        String freshness,
        Point position
    ) {}

    public record History(
        UUID deviceId,
        String sourceId,
        Instant from,
        Instant to,
        List<Point> points,
        int returnedCount,
        boolean truncated
    ) {}

    public record Evidence(Latest latest, History history) {}

    public record Fleet(List<Latest> fleet, List<History> tracks) {}
}
