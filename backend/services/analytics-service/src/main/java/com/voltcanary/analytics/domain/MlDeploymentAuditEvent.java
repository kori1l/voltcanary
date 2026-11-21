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
@Table(name = "ml_deployment_audit_events")
@Getter
@Setter
public class MlDeploymentAuditEvent {
    @Id
    private UUID id;
    @Column(name = "account_id")
    private UUID accountId;
    @Column(name = "model_version_id")
    private UUID modelVersionId;
    @Column(name = "policy_id")
    private UUID policyId;
    @Column(name = "action", nullable = false)
    private String action;
    @Column(name = "details_json", nullable = false, columnDefinition = "text")
    private String detailsJson;
    @Column(name = "actor_id")
    private UUID actorId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
