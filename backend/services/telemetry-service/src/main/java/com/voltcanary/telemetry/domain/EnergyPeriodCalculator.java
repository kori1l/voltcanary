package com.voltcanary.telemetry.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Consumes qualified recorded samples, never display bins or maintenance-filtered data. */
public final class EnergyPeriodCalculator {
    public enum Method { COUNTER_DELTA, POWER_INTEGRAL }
    public enum EventKind { RESET, ROLLOVER, REPLACEMENT }

    public record Reading(Instant measuredAt, double value, String unit, String sourceId, String quality) {
    }

    public record CounterEvent(Instant at, EventKind kind, Double rolloverWh) {
    }

    public record Policy(Method method, String sourceId, long maxGapSeconds, List<CounterEvent> events) {
        public Policy {
            if (method == null || sourceId == null || sourceId.isBlank() || maxGapSeconds < 1 || maxGapSeconds > 86400) {
                throw new IllegalArgumentException("A canonical method/source and bounded gap tolerance are required");
            }
            events = events == null ? List.of() : List.copyOf(events);
            for (CounterEvent event : events) {
                if (event == null || event.at() == null || event.kind() == null
                        || (event.kind() == EventKind.ROLLOVER && (event.rolloverWh() == null
                        || !Double.isFinite(event.rolloverWh()) || event.rolloverWh() <= 0))) {
                    throw new IllegalArgumentException("Counter events require explicit time/kind and rollover modulus");
                }
            }
        }
    }

    public record Result(Double energyKWh, String coverage, double coveredSeconds, double intervalSeconds,
            double coverageFraction, int acceptedSamples, int excludedSamples, int duplicateSamples,
            int conflictingTimestamps, int excessiveGaps, int resetIntervals, int rolloverIntervals,
            Double minimumPowerW, Double maximumPowerW, Instant maximumPowerAt, Instant measuredAt,
            boolean estimate, String boundaryPolicy, String qualityPolicy, String maintenancePolicy) {
    }

    private record Point(Instant at, double value, boolean valid) {
    }

    public Result calculate(List<Reading> readings, Instant from, Instant to, Policy policy) {
        if (readings == null || from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("A half-open interval of at most31 days is required");
        }
        Map<Instant, Point> normalized = new TreeMap<>();
        int excluded = 0;
        int duplicates = 0;
        Set<Instant> conflicts = new HashSet<>();
        for (Reading reading : readings) {
            if (reading == null || reading.measuredAt() == null || !policy.sourceId().equals(reading.sourceId())) {
                excluded++;
                continue;
            }
            Double value = canonical(reading, policy.method());
            boolean qualified = value != null && "OK".equals(reading.quality());
            if (!qualified) excluded++;
            Point previous = normalized.get(reading.measuredAt());
            Point current = new Point(reading.measuredAt(), qualified ? value : 0, qualified);
            if (previous == null) {
                normalized.put(current.at(), current);
            } else {
                duplicates++;
                if (previous.valid() && current.valid() && previous.value() != current.value()) {
                    normalized.put(current.at(), new Point(current.at(), 0, false));
                    conflicts.add(current.at());
                } else if (!previous.valid() && current.valid()) {
                    // A conflicting timestamp must never become valid because another duplicate arrives.
                    if (!conflicts.contains(current.at())) normalized.put(current.at(), current);
                }
            }
        }
        List<Point> points = new ArrayList<>(normalized.values());
        double energyWh = 0;
        double covered = 0;
        boolean interpolatedBoundary = false;
        int gaps = 0;
        int resets = 0;
        int rollovers = 0;
        int accepted = 0;
        Double minimum = null;
        Double maximum = null;
        Instant maximumAt = null;
        Instant measuredAt = null;
        for (Point point : points) {
            if (point.valid() && !point.at().isBefore(from) && point.at().isBefore(to)) {
                accepted++;
                measuredAt = point.at();
                if (policy.method() == Method.POWER_INTEGRAL) {
                    if (minimum == null || point.value() < minimum) minimum = point.value();
                    if (maximum == null || point.value() > maximum) {
                        maximum = point.value();
                        maximumAt = point.at();
                    }
                }
            }
        }
        for (int index = 1; index < points.size(); index++) {
            Point left = points.get(index - 1);
            Point right = points.get(index);
            Instant start = left.at().isBefore(from) ? from : left.at();
            Instant end = right.at().isAfter(to) ? to : right.at();
            if (!start.isBefore(end) || !left.valid() || !right.valid()) continue;
            double span = seconds(left.at(), right.at());
            if (span > policy.maxGapSeconds()) {
                gaps++;
                continue;
            }
            double duration = seconds(start, end);
            double startFraction = seconds(left.at(), start) / span;
            double endFraction = seconds(left.at(), end) / span;
            double amount;
            if (policy.method() == Method.COUNTER_DELTA) {
                List<CounterEvent> events = policy.events().stream().filter(event -> event.at().isAfter(left.at())
                        && !event.at().isAfter(right.at())).toList();
                double delta = right.value() - left.value();
                if (events.size() == 1 && events.getFirst().kind() == EventKind.ROLLOVER
                        && delta < 0 && left.value() < events.getFirst().rolloverWh()
                        && right.value() < events.getFirst().rolloverWh()) {
                    delta += events.getFirst().rolloverWh();
                    rollovers++;
                } else if (!events.isEmpty() || delta < 0) {
                    resets++;
                    continue;
                }
                amount = delta * (endFraction - startFraction);
            } else {
                double startPower = left.value() + (right.value() - left.value()) * startFraction;
                double endPower = left.value() + (right.value() - left.value()) * endFraction;
                amount = (startPower / 2 + endPower / 2) * duration / 3600;
            }
            if (!Double.isFinite(amount) || !Double.isFinite(energyWh + amount)) {
                throw new IllegalArgumentException("Energy calculation exceeds its numeric bounds");
            }
            energyWh += amount;
            covered += duration;
            interpolatedBoundary |= startFraction>0 || endFraction<1;
        }
        double interval = seconds(from, to);
        String coverage = covered == 0 ? "UNKNOWN" : covered >= interval - 0.000001 ? "COMPLETE" : "PARTIAL";
        return new Result(covered == 0 ? null : energyWh / 1000, coverage, covered, interval,
                Math.min(1, covered / interval), accepted, excluded, duplicates, conflicts.size(), gaps, resets, rollovers,
                minimum, maximum, maximumAt, measuredAt, policy.method() == Method.POWER_INTEGRAL || interpolatedBoundary,
                "LINEAR_BRACKETING_WITHIN_MAX_GAP_HALF_OPEN_UTC", "OK_ONLY_CONFLICTS_ARE_BARRIERS",
                "RAW_CONSUMPTION_INCLUDES_MAINTENANCE");
    }

    private Double canonical(Reading reading, Method method) {
        if (!Double.isFinite(reading.value()) || reading.value() < 0) return null;
        double scale = switch (String.valueOf(reading.unit())) {
            case "Wh" -> method == Method.COUNTER_DELTA ? 1 : 0;
            case "kWh" -> method == Method.COUNTER_DELTA ? 1000 : 0;
            case "W" -> method == Method.POWER_INTEGRAL ? 1 : 0;
            case "kW" -> method == Method.POWER_INTEGRAL ? 1000 : 0;
            default -> 0;
        };
        double value = reading.value() * scale;
        return scale > 0 && Double.isFinite(value) ? value : null;
    }

    private double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toNanos() / 1_000_000_000d;
    }
}
