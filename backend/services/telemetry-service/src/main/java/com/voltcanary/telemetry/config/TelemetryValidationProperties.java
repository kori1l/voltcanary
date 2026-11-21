package com.voltcanary.telemetry.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.telemetry.validation")
@Getter
public class TelemetryValidationProperties {

    @Setter
    private Duration maxFutureSkew = Duration.ofMinutes(5);
    @Setter
    private Duration maxPastAge = Duration.ofDays(7);
    @Setter
    private int maxMetricsPerEvent = 100;
    @Setter
    private int maxEventsPerDevicePerMinute = 600;
    @Setter
    private boolean redisRateLimitEnabled = true;
    @Setter
    private Duration maxChartRange = Duration.ofDays(30);
    @Setter
    private int maxExportPageSize = 1000;
    private List<String> backfillSourcePrefixes = new ArrayList<>(List.of("import-", "backfill-"));

    public void setBackfillSourcePrefixes(List<String> backfillSourcePrefixes) {
        this.backfillSourcePrefixes = backfillSourcePrefixes == null ? List.of() : List.copyOf(backfillSourcePrefixes);
    }
}
