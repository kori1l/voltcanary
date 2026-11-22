package com.voltcanary.analytics.service;

import com.voltcanary.analytics.config.AnalyticsMetricProperties;
import com.voltcanary.analytics.domain.MaintenanceWindowReadModel;
import com.voltcanary.analytics.dto.AlertRuleSnapshot;
import com.voltcanary.analytics.telemetry.TelemetryChartResponse;
import com.voltcanary.analytics.telemetry.TelemetryClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalyticsInputReader {
    public record Input(UUID device, Instant now, Instant from, Instant to, String period, List<String> metrics,
            AnalyticsResourceContext.Context context, List<TelemetryChartResponse> charts,
            Map<String, TelemetryChartResponse> chartByMetric, List<AlertRuleSnapshot> rules,
            int excludedSamples, String inputHash) { }
    private final AnalyticsMetricProperties properties;
    private final AnalyticsResourceContext resources;
    private final TelemetryClient telemetry;
    private final AlertRuleClient alertRules;
    private final AnalyticsMaintenanceTelemetryFilterService filter;
    private final AnalyticsPredictionTraceService trace;

    public Instant windowStart(Instant end) { return end.minus(properties.getDefaultWindow()); }

    public Input read(UUID device, Instant end) {
        Instant now = Instant.now();
        Instant from = windowStart(end);
        var context = resources.read(device, from, end);
        var exclusions = context.windows().stream().filter(MaintenanceWindowReadModel::isExcludeFromAnalytics).toList();
        List<String> metrics = List.copyOf(properties.getDefaultMetrics());
        var charts = new ArrayList<TelemetryChartResponse>();
        var byMetric = new HashMap<String, TelemetryChartResponse>();
        int excluded = 0;
        for (String metric : metrics) {
            var raw = telemetry.readings(device, metric, from, end);
            var chart = filter.filterChart(raw, exclusions);
            excluded += filter.excludedSampleCount(raw, chart);
            charts.add(chart);
            byMetric.put(metric, chart);
        }
        String hash = trace.inputHash(device, from, end, charts, context.windows());
        var rules = alertRules.effectiveRules(device, hash);
        for (var rule : rules) {
            if (rule.metric() != null && !rule.metric().isBlank() && !byMetric.containsKey(rule.metric())) {
                byMetric.put(rule.metric(), filter.filterChart(telemetry.readings(device, rule.metric(), from, end), exclusions));
            }
        }
        return new Input(device, now, from, end, properties.getPeriodLabel(), metrics, context,
                Collections.unmodifiableList(charts), Collections.unmodifiableMap(byMetric),
                List.copyOf(rules), excluded, hash);
    }
}
