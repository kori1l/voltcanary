package com.voltcanary.telemetry.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.telemetry.validation")
public class TelemetryValidationProperties {

    private Duration maxFutureSkew = Duration.ofMinutes(5);
    private Duration maxPastAge = Duration.ofDays(7);
    private int maxMetricsPerEvent = 100;
    private int maxEventsPerDevicePerMinute = 600;
    private boolean redisRateLimitEnabled = true;
    private Duration maxChartRange = Duration.ofDays(30);
    private int maxExportPageSize = 1000;
    private List<String> backfillSourcePrefixes = new ArrayList<>(List.of("import-", "backfill-"));

    public Duration getMaxFutureSkew() {
        return maxFutureSkew;
    }

    public void setMaxFutureSkew(Duration maxFutureSkew) {
        this.maxFutureSkew = maxFutureSkew;
    }

    public Duration getMaxPastAge() {
        return maxPastAge;
    }

    public void setMaxPastAge(Duration maxPastAge) {
        this.maxPastAge = maxPastAge;
    }

    public int getMaxMetricsPerEvent() {
        return maxMetricsPerEvent;
    }

    public void setMaxMetricsPerEvent(int maxMetricsPerEvent) {
        this.maxMetricsPerEvent = maxMetricsPerEvent;
    }

    public int getMaxEventsPerDevicePerMinute() {
        return maxEventsPerDevicePerMinute;
    }

    public void setMaxEventsPerDevicePerMinute(int maxEventsPerDevicePerMinute) {
        this.maxEventsPerDevicePerMinute = maxEventsPerDevicePerMinute;
    }

    public boolean isRedisRateLimitEnabled() {
        return redisRateLimitEnabled;
    }

    public void setRedisRateLimitEnabled(boolean redisRateLimitEnabled) {
        this.redisRateLimitEnabled = redisRateLimitEnabled;
    }

    public Duration getMaxChartRange() {
        return maxChartRange;
    }

    public void setMaxChartRange(Duration maxChartRange) {
        this.maxChartRange = maxChartRange;
    }

    public int getMaxExportPageSize() {
        return maxExportPageSize;
    }

    public void setMaxExportPageSize(int maxExportPageSize) {
        this.maxExportPageSize = maxExportPageSize;
    }

    public List<String> getBackfillSourcePrefixes() {
        return backfillSourcePrefixes;
    }

    public void setBackfillSourcePrefixes(List<String> backfillSourcePrefixes) {
        this.backfillSourcePrefixes = backfillSourcePrefixes == null ? List.of() : List.copyOf(backfillSourcePrefixes);
    }
}
