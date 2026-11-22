package com.voltcanary.analytics.service;

import com.voltcanary.analytics.domain.AnalyticsJob;
import com.voltcanary.analytics.domain.Anomaly;
import com.voltcanary.analytics.domain.DeviceBaseline;
import com.voltcanary.analytics.domain.FeatureSet;
import com.voltcanary.analytics.domain.PredictionResult;
import com.voltcanary.analytics.domain.Recommendation;
import com.voltcanary.analytics.domain.RiskScore;
import com.voltcanary.analytics.producer.AnalyticsOutboxService;
import com.voltcanary.analytics.repository.AnalyticsJobRepository;
import com.voltcanary.analytics.repository.AnomalyRepository;
import com.voltcanary.analytics.repository.RecommendationRepository;
import com.voltcanary.analytics.repository.RiskScoreRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a fully computed result and its events under the device deletion fence. No network I/O. */
@Service
@RequiredArgsConstructor
public class AnalyticsResultStore {

    public enum Outcome {
        SAVED,
        DEVICE_DELETED,
        JOB_LEASE_LOST,
    }

    private final DeviceHistoryDeletion deletions;
    private final AnalyticsJobRepository jobs;
    private final EntityManager entityManager;
    private final AnomalyRepository anomalies;
    private final RiskScoreRepository risks;
    private final RecommendationRepository recommendations;
    private final AnalyticsOutboxService outbox;

    @Transactional
    public Outcome save(
        UUID deviceId,
        AnalyticsJob expected,
        Instant calculatedAt,
        List<DeviceBaseline> newBaselines,
        List<FeatureSet> newFeatures,
        List<Anomaly> newAnomalies,
        RiskScore risk,
        List<PredictionResult> newPredictions,
        List<Recommendation> newRecommendations
    ) {
        if (!deletions.writable(deviceId)) return Outcome.DEVICE_DELETED;
        AnalyticsJob actual = null;
        if (expected != null) {
            actual = jobs.lockById(expected.getId()).orElse(null);
            if (actual == null) return Outcome.JOB_LEASE_LOST;
            entityManager.refresh(actual);
            if (
                !"RUNNING".equals(actual.getStatus()) ||
                !Objects.equals(actual.getLockedBy(), expected.getLockedBy()) ||
                !Objects.equals(actual.getLockedAt(), expected.getLockedAt()) ||
                actual.getLockedAt() == null ||
                actual.getLockedAt().isBefore(Instant.now().minusSeconds(900))
            ) return Outcome.JOB_LEASE_LOST;
        }
        double previousRisk = risks
            .findFirstByDeviceIdOrderByCalculatedAtDesc(deviceId)
            .map(RiskScore::getScore)
            .orElse(0.0);
        newBaselines.forEach(entityManager::persist);
        newFeatures.forEach(entityManager::persist);
        anomalies.supersedeActiveByDeviceId(deviceId, calculatedAt);
        recommendations.supersedeActiveByDeviceId(deviceId, calculatedAt);
        newAnomalies.forEach(entityManager::persist);
        entityManager.persist(risk);
        newPredictions.forEach(entityManager::persist);
        newRecommendations.forEach(entityManager::persist);
        newAnomalies.forEach(outbox::enqueueAnomaly);
        outbox.enqueueRiskScore(risk, previousRisk);
        if (actual != null) {
            actual.setStatus("COMPLETED");
            actual.setActiveKey(null);
            actual.setCompletedAt(Instant.now());
            actual.setWindowStart(risk.getWindowStart());
            actual.setWindowEnd(risk.getWindowEnd());
            actual.setInputHash(risk.getInputHash());
            actual.setLockedAt(null);
            actual.setLockedBy(null);
            actual.setErrorMessage(null);
            actual.setSkipReason(null);
        }
        return Outcome.SAVED;
    }
}
