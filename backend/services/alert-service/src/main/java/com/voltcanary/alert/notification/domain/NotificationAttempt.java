package com.voltcanary.alert.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notification_attempts")
@Getter
@Setter
public class NotificationAttempt {

    @Id
    private UUID id;

    @Column(name = "alert_id")
    private UUID alertId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "channel_type", nullable = false)
    private String channelType;

    @Column(name = "delivery_event_type", nullable = false)
    private String deliveryEventType;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationAttemptStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "retry_exhausted", nullable = false)
    private boolean retryExhausted;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "provider_response_summary")
    private String providerResponseSummary;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "request_payload_json")
    private String requestPayloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

}
