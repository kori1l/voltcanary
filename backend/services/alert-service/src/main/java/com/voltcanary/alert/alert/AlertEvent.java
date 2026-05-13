package com.voltcanary.alert.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_events")
public class AlertEvent {

    @Id
    private UUID id;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
