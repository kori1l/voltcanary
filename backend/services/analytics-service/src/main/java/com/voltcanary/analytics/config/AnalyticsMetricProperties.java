package com.voltcanary.analytics.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.metrics")
public class AnalyticsMetricProperties {

    private List<String> defaultMetrics = new ArrayList<>(List.of("activePowerW"));
    private Duration defaultWindow = Duration.ofHours(24);
    private String periodLabel = "last_24h";

    public List<String> getDefaultMetrics() {
        return defaultMetrics;
    }

    public void setDefaultMetrics(List<String> defaultMetrics) {
        this.defaultMetrics = defaultMetrics == null || defaultMetrics.isEmpty()
                ? new ArrayList<>(List.of("activePowerW"))
                : defaultMetrics;
    }

    public Duration getDefaultWindow() {
        return defaultWindow;
    }

    public void setDefaultWindow(Duration defaultWindow) {
        this.defaultWindow = defaultWindow;
    }

    public String getPeriodLabel() {
        return periodLabel;
    }

    public void setPeriodLabel(String periodLabel) {
        this.periodLabel = periodLabel;
    }
}
