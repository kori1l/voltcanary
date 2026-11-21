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
@Table(name = "analytics_jobs")
@Getter
@Setter
public class AnalyticsJob {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "active_key")
    private String activeKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "input_hash")
    private String inputHash;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "skip_reason")
    private String skipReason;

}
