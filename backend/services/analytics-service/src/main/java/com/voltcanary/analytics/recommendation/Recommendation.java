package com.voltcanary.analytics.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recommendations")
public class Recommendation {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
