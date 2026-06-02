package com.voltcanary.analytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.retention")
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getBaselines() {
        return baselines;
    }

    public void setBaselines(Duration baselines) {
        this.baselines = baselines;
    }

    public Duration getFeatureSets() {
        return featureSets;
    }

    public void setFeatureSets(Duration featureSets) {
        this.featureSets = featureSets;
    }

    public Duration getPredictions() {
        return predictions;
    }

    public void setPredictions(Duration predictions) {
        this.predictions = predictions;
    }

    public Duration getAnomalies() {
        return anomalies;
    }

    public void setAnomalies(Duration anomalies) {
        this.anomalies = anomalies;
    }

    public Duration getRiskScores() {
        return riskScores;
    }

    public void setRiskScores(Duration riskScores) {
        this.riskScores = riskScores;
    }

    public Duration getJobs() {
        return jobs;
    }

    public void setJobs(Duration jobs) {
        this.jobs = jobs;
    }

    public Duration getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(Duration recommendations) {
        this.recommendations = recommendations;
    }

    public Duration getOutbox() {
        return outbox;
    }

    public void setOutbox(Duration outbox) {
        this.outbox = outbox;
    }
}
