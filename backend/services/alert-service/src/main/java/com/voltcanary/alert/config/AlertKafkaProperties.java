package com.voltcanary.alert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.alert.kafka")
public class AlertKafkaProperties {

    private final Retry retry = new Retry();
    private final Dlq dlq = new Dlq();

    public Retry getRetry() {
        return retry;
    }

    public Dlq getDlq() {
        return dlq;
    }

    public static class Retry {
        private int maxAttempts = 3;
        private Duration interval = Duration.ofSeconds(1);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }

    public static class Dlq {
        private String anomalyTopic = "anomaly.detected.dlq.v1";
        private String riskScoreTopic = "risk-score.changed.dlq.v1";
        private String maintenanceWindowTopic = "maintenance-window.changed.dlq.v1";

        public String getAnomalyTopic() {
            return anomalyTopic;
        }

        public void setAnomalyTopic(String anomalyTopic) {
            this.anomalyTopic = anomalyTopic;
        }

        public String getRiskScoreTopic() {
            return riskScoreTopic;
        }

        public void setRiskScoreTopic(String riskScoreTopic) {
            this.riskScoreTopic = riskScoreTopic;
        }

        public String getMaintenanceWindowTopic() {
            return maintenanceWindowTopic;
        }

        public void setMaintenanceWindowTopic(String maintenanceWindowTopic) {
            this.maintenanceWindowTopic = maintenanceWindowTopic;
        }
    }
}
