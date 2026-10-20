package com.voltcanary.simulator;

import com.voltcanary.simulator.domain.SimulationProfile;
import com.voltcanary.simulator.generator.SimulationContext;
import com.voltcanary.simulator.generator.VehicleGpsGenerator;
import com.voltcanary.simulator.yaml.SimulationTemplates;
import com.voltcanary.simulator.yaml.SimulationYamlParser;
import com.voltcanary.simulator.yaml.SimulationYamlValidator;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VehicleGpsGeneratorTest {
    private final SimulationYamlParser parser = new SimulationYamlParser();
    private final String yaml = new SimulationTemplates().template(SimulationProfile.VEHICLE_GPS, "sim-car-001");

    @Test void profileValidationDoesNotReuseApplianceMetrics() {
        var validator = new SimulationYamlValidator(parser);
        assertThat(validator.validate(yaml)).isEmpty();
        for (String invalid : new String[]{yaml.replace("CITY_LOOP", "UNKNOWN"), yaml.replace("speedKmh: 36", "speedKmh: 121"),
                yaml.replace("interval: 5s", "interval: 31s"), yaml.replace("protocol: HTTP", "protocol: MQTT"),
                yaml.replace("routeId: CITY_LOOP", "routeId: CITY_LOOP\n  unsupported: true"),
                yaml.replace("type: generated", "type: constant"), yaml.replace("startDelay: 0s", "startDelay: 1s")}) {
            assertThat(validator.validate(invalid)).isNotEmpty();
        }
        assertThat(validator.validate(yaml.replace("VEHICLE_GPS", "FRIDGE"))).isNotEmpty();
    }

    @Test void monotonicMovementKeepsActualTimestampAndRestartsAtOrigin() {
        AtomicLong clock = new AtomicLong();
        var generator = new VehicleGpsGenerator(clock::get);
        UUID run = UUID.randomUUID();
        Instant now = Instant.now();
        var config = parser.parse(yaml);
        var context = new SimulationContext(run, UUID.randomUUID(), UUID.randomUUID(), "sim-car-001", now, now, 1, config);
        var first = generator.next(context).orElseThrow();
        assertThat(first.metrics()).containsEntry("latitude", 44.8125).containsEntry("longitude", 20.4612);
        clock.set(5_000_000_000L);
        var next = generator.next(context).orElseThrow();
        assertThat(next.timestamp()).isEqualTo(now);
        double lat = (double) next.metrics().get("latitude");
        double lon = (double) next.metrics().get("longitude");
        double travelled = Math.hypot((lat - 44.8125) * 111195, (lon - 20.4612) * 111195 * Math.cos(Math.toRadians(lat)));
        assertThat(travelled).isBetween(49.8, 50.2);
        assertThat((double) next.metrics().get("headingDeg")).isBetween(0.0, 359.99);
        clock.set(1800_000_000_000L);
        assertThat(generator.next(context).orElseThrow().metrics().get("latitude")).isNotEqualTo(44.8125);
        generator.release(run);
        assertThat(generator.next(context).orElseThrow().metrics()).isEqualTo(first.metrics());
    }

    @Test void customRouteStopsGenerateOptionalVehicleMetrics() {
        String custom = yaml.replace("  speedKmh: 36", "  speedKmh: 36\n  vehicleMetrics: true\n  initialOdometerKm: 100\n  waypoints:\n    - {latitude: 0, longitude: 0, stopSeconds: 10}\n    - {latitude: 0, longitude: 0.001, stopSeconds: 0}")
                + "  ignition:\n    type: generated\n  fuelPercent:\n    type: generated\n  odometerKm:\n    type: generated\n";
        assertThat(new SimulationYamlValidator(parser).validate(custom)).isEmpty();
        assertThat(new SimulationYamlValidator(parser).validate(custom.replace("stopSeconds: 10", "stopSeconds: -1"))).isNotEmpty();
        AtomicLong clock = new AtomicLong();
        var generator = new VehicleGpsGenerator(clock::get);
        var now = Instant.now();
        var context = new SimulationContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"gps",now,now,1,parser.parse(custom));
        var stopped = generator.next(context).orElseThrow().metrics();
        assertThat(stopped).containsEntry("speedKmh",0.0).containsEntry("ignition",0.0).containsEntry("odometerKm",100.0);
        clock.set(15_000_000_000L);
        var moved = generator.next(context).orElseThrow().metrics();
        assertThat(moved).containsEntry("speedKmh",36.0).containsEntry("ignition",1.0);
        assertThat((double)moved.get("odometerKm")).isBetween(100.049,100.051);
        assertThat((double)moved.get("fuelPercent")).isLessThan(80);
    }
}
