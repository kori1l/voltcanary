package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "risk_scores")
@Getter
@Setter
public class RiskScore {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "level", nullable = false)
    private String level;

    @Column(name = "explanation_json", columnDefinition = "text")
    private String explanationJson;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "input_hash")
    private String inputHash;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;
}
