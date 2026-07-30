package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ml_deployment_audit_events")
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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getModelVersionId() { return modelVersionId; }
    public void setModelVersionId(UUID modelVersionId) { this.modelVersionId = modelVersionId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String detailsJson) { this.detailsJson = detailsJson; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
