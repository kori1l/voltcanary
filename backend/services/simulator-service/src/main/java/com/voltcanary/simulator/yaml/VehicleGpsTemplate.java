package com.voltcanary.simulator.yaml;

public final class VehicleGpsTemplate {

    private VehicleGpsTemplate() {}

    public static String yaml(String sourceId) {
        return """
        schemaVersion: 1
        simulator:
          name: vehicle-gps-simulator
          profile: VEHICLE_GPS
          mode: NORMAL
          protocol: HTTP
        target:
          sourceId: "%s"
        schedule:
          interval: 5s
          startDelay: 0s
          duration: 30m
          speed: 1.0
        gps:
          routeId: CITY_LOOP
          speedKmh: 36
        metrics:
          latitude:
            type: generated
          longitude:
            type: generated
          speedKmh:
            type: generated
          headingDeg:
            type: generated
        """.formatted(
            sourceId.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
        );
    }
}
