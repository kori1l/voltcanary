package com.voltcanary.analytics.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.metrics")
@Getter
public class AnalyticsMetricProperties {

    private List<String> defaultMetrics = new ArrayList<>(List.of("activePowerW"));
    @Setter
    private Duration defaultWindow = Duration.ofHours(24);
    @Setter
    private String periodLabel = "last_24h";

    public void setDefaultMetrics(List<String> defaultMetrics) {
        this.defaultMetrics = defaultMetrics == null || defaultMetrics.isEmpty()
                ? new ArrayList<>(List.of("activePowerW"))
                : defaultMetrics;
    }

}
