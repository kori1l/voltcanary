package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsMaintenanceTelemetryFilterService {

    private final MeterRegistry meterRegistry;

    public AnalyticsMaintenanceTelemetryFilterService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public TelemetryChartResponse filterChart(
            TelemetryChartResponse chart,
            List<MaintenanceWindowReadModel> analyticsExclusions) {
        if (chart == null || chart.points() == null || chart.points().isEmpty() || analyticsExclusions.isEmpty()) {
            return chart;
        }
        return new TelemetryChartResponse(
                chart.deviceId(),
                chart.metric(),
                chart.points().stream()
                        .filter(point -> !isInsideAny(point.timestamp(), analyticsExclusions))
                        .toList());
    }

    public int excludedSampleCount(TelemetryChartResponse rawChart, TelemetryChartResponse filteredChart) {
        int raw = rawChart == null || rawChart.points() == null ? 0 : rawChart.points().size();
        int filtered = filteredChart == null || filteredChart.points() == null ? 0 : filteredChart.points().size();
        int excluded = raw - filtered;
        if (excluded > 0) {
            meterRegistry.counter("analytics.maintenance.samples_excluded").increment(excluded);
        }
        return Math.max(excluded, 0);
    }

    private boolean isInsideAny(Instant timestamp, List<MaintenanceWindowReadModel> windows) {
        return windows.stream().anyMatch(window ->
                !timestamp.isBefore(window.getStartAt()) && timestamp.isBefore(window.getEndAt()));
    }
}
