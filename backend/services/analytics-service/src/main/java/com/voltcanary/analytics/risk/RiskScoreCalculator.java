package com.voltcanary.analytics.risk;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RiskScoreCalculator {

    public void recalculate(UUID deviceId) {
        // Risk model will be implemented after anomaly and baseline pipelines are ready.
    }
}
