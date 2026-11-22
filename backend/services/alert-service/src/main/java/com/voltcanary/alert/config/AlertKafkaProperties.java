package com.voltcanary.alert.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.alert.kafka")
@Getter
public class AlertKafkaProperties {

    private final Retry retry = new Retry();
    private final Dlq dlq = new Dlq();

    public static class Retry {

        @Getter
        @Setter
        private int maxAttempts = 3;

        @Getter
        @Setter
        private Duration interval = Duration.ofSeconds(1);
    }

    public static class Dlq {

        @Getter
        @Setter
        private String anomalyTopic = "anomaly.detected.dlq.v1";

        @Getter
        @Setter
        private String riskScoreTopic = "risk-score.changed.dlq.v1";

        @Getter
        @Setter
        private String maintenanceWindowTopic = "maintenance-window.changed.dlq.v1";
    }
}
