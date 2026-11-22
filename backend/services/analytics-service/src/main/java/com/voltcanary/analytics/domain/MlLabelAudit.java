package com.voltcanary.analytics.domain;

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
@Table(name = "ml_label_audit")
@Getter
@Setter
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
}
