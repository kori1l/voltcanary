package com.voltcanary.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.rules")
public class AnalyticsRuleProperties {

    private String version = "rules-v1";
    private PowerSpike powerSpike = new PowerSpike();
    private ConsumptionGrowth consumptionGrowth = new ConsumptionGrowth();
    private LongCycle longCycle = new LongCycle();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public PowerSpike getPowerSpike() {
        return powerSpike;
    }

    public void setPowerSpike(PowerSpike powerSpike) {
        this.powerSpike = powerSpike;
    }

    public ConsumptionGrowth getConsumptionGrowth() {
        return consumptionGrowth;
    }

    public void setConsumptionGrowth(ConsumptionGrowth consumptionGrowth) {
        this.consumptionGrowth = consumptionGrowth;
    }

    public LongCycle getLongCycle() {
        return longCycle;
    }

    public void setLongCycle(LongCycle longCycle) {
        this.longCycle = longCycle;
    }

    public static class PowerSpike {
        private boolean enabled = true;
        private double medianMultiplier = 1.8;
        private double meanMultiplier = 1.2;
        private double score = 30;
        private String severity = "MEDIUM";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMedianMultiplier() {
            return medianMultiplier;
        }

        public void setMedianMultiplier(double medianMultiplier) {
            this.medianMultiplier = medianMultiplier;
        }

        public double getMeanMultiplier() {
            return meanMultiplier;
        }

        public void setMeanMultiplier(double meanMultiplier) {
            this.meanMultiplier = meanMultiplier;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }
    }

    public static class ConsumptionGrowth {
        private boolean enabled = true;
        private int minPoints = 4;
        private double growthMultiplier = 1.15;
        private double score = 30;
        private String severity = "MEDIUM";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinPoints() {
            return minPoints;
        }

        public void setMinPoints(int minPoints) {
            this.minPoints = minPoints;
        }

        public double getGrowthMultiplier() {
            return growthMultiplier;
        }

        public void setGrowthMultiplier(double growthMultiplier) {
            this.growthMultiplier = growthMultiplier;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }
    }

    public static class LongCycle {
        private boolean enabled = true;
        private int minPoints = 8;
        private int minHighPoints = 4;
        private double score = 20;
        private String severity = "LOW";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinPoints() {
            return minPoints;
        }

        public void setMinPoints(int minPoints) {
            this.minPoints = minPoints;
        }

        public int getMinHighPoints() {
            return minHighPoints;
        }

        public void setMinHighPoints(int minHighPoints) {
            this.minHighPoints = minHighPoints;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }
    }
}
