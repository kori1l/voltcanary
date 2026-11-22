package com.voltcanary.telemetry.service;

import com.voltcanary.telemetry.domain.EnergyPeriodCalculator;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.PreparedReadings;
import com.voltcanary.telemetry.domain.EnergyPeriodCalculator.Reading;
import com.voltcanary.telemetry.repository.EnergyReadingRepository.StoredReading;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One request-local grouping; interval lookups do not walk unrelated samples. */
final class EnergyReadingIndex {

    private record Stream(UUID deviceId, String sourceId, String metric) {}

    private final Map<Stream, PreparedReadings> streams = new HashMap<>();
    private final EnergyPeriodCalculator calculator = new EnergyPeriodCalculator();

    EnergyReadingIndex(Map<UUID, List<StoredReading>> stored) {
        Map<Stream, List<Reading>> grouped = new HashMap<>();
        stored.forEach((device, samples) -> {
            for (StoredReading sample : samples) {
                var key = new Stream(device, sample.reading().sourceId(), sample.metric());
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sample.reading());
            }
        });
        grouped.forEach((key, samples) ->
            streams.put(key, calculator.prepare(samples, method(key.metric()), key.sourceId()))
        );
    }

    PreparedReadings slice(
        UUID device,
        EnergyMeterWindows.Window window,
        String metric,
        Instant readFrom,
        Instant readTo
    ) {
        var key = new Stream(device, window.definition().sourceId(), metric);
        PreparedReadings samples = streams.get(key);
        if (samples == null) samples = calculator.prepare(
            List.of(),
            method(metric),
            key.sourceId()
        );
        Instant start = readFrom.isAfter(window.from()) ? readFrom : window.from();
        boolean inclusiveEnd = window.to() == null || readTo.isBefore(window.to());
        Instant end = inclusiveEnd ? readTo : window.to();
        if (start.isAfter(end) || (start.equals(end) && !inclusiveEnd)) {
            return calculator.prepare(List.of(), method(metric), key.sourceId());
        }
        return samples.between(start, end, inclusiveEnd);
    }

    private EnergyPeriodCalculator.Method method(String metric) {
        return "energyWh".equals(metric)
            ? EnergyPeriodCalculator.Method.COUNTER_DELTA
            : EnergyPeriodCalculator.Method.POWER_INTEGRAL;
    }
}
