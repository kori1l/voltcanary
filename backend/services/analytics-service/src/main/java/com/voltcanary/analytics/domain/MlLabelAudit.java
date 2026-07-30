package com.voltcanary.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ml_label_audit")
public class MlLabelAudit {
    @Id
    private UUID id;
    @Column(name = "label_id", nullable = false)
    private UUID labelId;
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_label")
    private MlLabelType previousLabel;
    @Enumerated(EnumType.STRING)
    @Column(name = "new_label", nullable = false)
    private MlLabelType newLabel;
    @Column(name = "edited_by")
    private UUID editedBy;
    @Column(name = "edited_at", nullable = false)
    private Instant editedAt;
    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getLabelId() { return labelId; }
    public void setLabelId(UUID labelId) { this.labelId = labelId; }
    public MlLabelType getPreviousLabel() { return previousLabel; }
    public void setPreviousLabel(MlLabelType previousLabel) { this.previousLabel = previousLabel; }
    public MlLabelType getNewLabel() { return newLabel; }
    public void setNewLabel(MlLabelType newLabel) { this.newLabel = newLabel; }
    public UUID getEditedBy() { return editedBy; }
    public void setEditedBy(UUID editedBy) { this.editedBy = editedBy; }
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
