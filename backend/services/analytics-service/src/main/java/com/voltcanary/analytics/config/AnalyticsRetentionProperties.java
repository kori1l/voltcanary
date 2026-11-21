package com.voltcanary.analytics.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.retention")
@Getter
@Setter
public class AnalyticsRetentionProperties {

    private boolean enabled = true;
    private Duration baselines = Duration.ofDays(180);
    private Duration featureSets = Duration.ofDays(90);
    private Duration predictions = Duration.ofDays(90);
    private Duration anomalies = Duration.ofDays(180);
    private Duration riskScores = Duration.ofDays(180);
    private Duration jobs = Duration.ofDays(30);
    private Duration recommendations = Duration.ofDays(180);
    private Duration outbox = Duration.ofDays(7);

}
