package com.voltcanary.simulator.generator;

import com.voltcanary.simulator.yaml.GpsConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** A local closed contour near central Belgrade; no external routing dependency. */
@Component
public class VehicleGpsGenerator implements TelemetryGenerator {

    private static final double[][] ROUTE = {
        { 44.8125, 20.4612 },
        { 44.8135, 20.4638 },
        { 44.8119, 20.4660 },
        { 44.8097, 20.4634 },
        { 44.8108, 20.4602 },
        { 44.8125, 20.4612 },
    };
    private final ConcurrentHashMap<UUID, RouteRun> runs = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    private record RouteRun(
        long origin,
        List<GpsConfig.Waypoint> points,
        double[] lengths,
        double length,
        double seconds
    ) {}

    public VehicleGpsGenerator() {
        this(System::nanoTime);
    }

    public VehicleGpsGenerator(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public Optional<GeneratedTelemetryPoint> next(SimulationContext context) {
        long now = clock.getAsLong();
        GpsConfig gps = context.config().gps();
        RouteRun run = runs.computeIfAbsent(context.runId(), ignored -> route(now, gps));
        double elapsed = Math.max(0, now - run.origin()) / 1e9;
        double travelled = Math.floor(elapsed / run.seconds()) * run.length();
        double remaining = elapsed % run.seconds();
        int segment = 0;
        boolean stopped = false;
        double fraction = 0;
        for (; segment < run.points().size(); segment++) {
            int stop = run.points().get(segment).stopSeconds();
            if (remaining < stop) {
                stopped = true;
                break;
            }
            remaining -= stop;
            double seconds = run.lengths()[segment] / (gps.speedKmh() / 3.6);
            if (remaining < seconds) {
                fraction = remaining / seconds;
                travelled += fraction * run.lengths()[segment];
                break;
            }
            remaining -= seconds;
            travelled += run.lengths()[segment];
        }
        segment = Math.min(segment, run.points().size() - 1);
        if (gps.vehicleMetrics() && gps.consumptionLitresPer100Km() > 0) {
            double availableMetres =
                ((gps.initialFuelPercent() * gps.fuelTankLitres()) /
                    gps.consumptionLitresPer100Km()) *
                1000;
            if (travelled >= availableMetres) {
                travelled = availableMetres;
                double routeDistance = travelled % run.length();
                segment = 0;
                while (
                        segment < run.lengths().length - 1 &&
                        routeDistance >= run.lengths()[segment]
                    )
                    routeDistance -= run.lengths()[segment++];
                fraction = run.lengths()[segment] == 0 ? 0 : routeDistance / run.lengths()[segment];
                stopped = true;
            }
        }
        var from = run.points().get(segment);
        var to = run.points().get((segment + 1) % run.points().size());
        double[] a = { from.latitude(), from.longitude() },
            b = { to.latitude(), to.longitude() };
        double lonDelta = ((b[1] - a[1] + 540) % 360) - 180;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("latitude", a[0] + fraction * (b[0] - a[0]));
        double longitude = a[1] + fraction * lonDelta;
        metrics.put(
            "longitude",
            longitude > 180 ? longitude - 360 : longitude < -180 ? longitude + 360 : longitude
        );
        metrics.put("speedKmh", stopped ? 0.0 : gps.speedKmh());
        metrics.put("headingDeg", heading(a, b));
        if (gps.vehicleMetrics()) {
            metrics.put("ignition", stopped ? 0.0 : 1.0);
            metrics.put("odometerKm", gps.initialOdometerKm() + travelled / 1000);
            metrics.put(
                "fuelPercent",
                Math.max(
                    0,
                    gps.initialFuelPercent() -
                        ((travelled / 1000) * gps.consumptionLitresPer100Km()) /
                            gps.fuelTankLitres()
                )
            );
        }
        return Optional.of(
            new GeneratedTelemetryPoint(context.currentTime(), context.sourceId(), metrics)
        );
    }

    private RouteRun route(long now, GpsConfig config) {
        List<GpsConfig.Waypoint> points = config.waypoints().isEmpty()
            ? java.util.Arrays.stream(ROUTE)
                  .limit(ROUTE.length - 1)
                  .map(p -> new GpsConfig.Waypoint(p[0], p[1], 0))
                  .toList()
            : config.waypoints();
        double[] lengths = new double[points.size()];
        double sum = 0,
            seconds = 0;
        for (int i = 0; i < points.size(); i++) {
            var a = points.get(i);
            var b = points.get((i + 1) % points.size());
            lengths[i] = distance(
                new double[] { a.latitude(), a.longitude() },
                new double[] { b.latitude(), b.longitude() }
            );
            sum += lengths[i];
            seconds += a.stopSeconds() + lengths[i] / (config.speedKmh() / 3.6);
        }
        if (
            !Double.isFinite(seconds) || sum <= 0 || seconds <= 0
        ) throw new IllegalArgumentException("Route must have nonzero length");
        return new RouteRun(now, points, lengths, sum, seconds);
    }

    public void release(UUID runId) {
        runs.remove(runId);
    }

    private static double distance(double[] a, double[] b) {
        double lat = Math.toRadians(b[0] - a[0]);
        double lon = Math.toRadians(b[1] - a[1]);
        double h =
            Math.pow(Math.sin(lat / 2), 2) +
            Math.cos(Math.toRadians(a[0])) *
                Math.cos(Math.toRadians(b[0])) *
                Math.pow(Math.sin(lon / 2), 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private static double heading(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[0]);
        double lat2 = Math.toRadians(b[0]);
        double lon = Math.toRadians(b[1] - a[1]);
        double y = Math.sin(lon) * Math.cos(lat2);
        double x =
            Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }
}
