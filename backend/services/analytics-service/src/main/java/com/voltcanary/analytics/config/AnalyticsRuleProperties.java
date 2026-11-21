package com.voltcanary.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.analytics.rules")
@Getter
@Setter
public class AnalyticsRuleProperties {

    private String version = "rules-v1";
    private PowerSpike powerSpike = new PowerSpike();
    private ConsumptionGrowth consumptionGrowth = new ConsumptionGrowth();
    private LongCycle longCycle = new LongCycle();

    public static class PowerSpike {
        @Getter
        @Setter
        private boolean enabled = true;
        @Getter
        @Setter
        private double medianMultiplier = 1.8;
        @Getter
        @Setter
        private double meanMultiplier = 1.2;
        @Getter
        @Setter
        private double score = 30;
        @Getter
        @Setter
        private String severity = "MEDIUM";

    }

    public static class ConsumptionGrowth {
        @Getter
        @Setter
        private boolean enabled = true;
        @Getter
        @Setter
        private int minPoints = 4;
        @Getter
        @Setter
        private double growthMultiplier = 1.15;
        @Getter
        @Setter
        private double score = 30;
        @Getter
        @Setter
        private String severity = "MEDIUM";

    }

    public static class LongCycle {
        @Getter
        @Setter
        private boolean enabled = true;
        @Getter
        @Setter
        private int minPoints = 8;
        @Getter
        @Setter
        private int minHighPoints = 4;
        @Getter
        @Setter
        private double score = 20;
        @Getter
        @Setter
        private String severity = "LOW";

    }
}
