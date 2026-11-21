package com.voltcanary.simulator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "simulation_run_events")
@Getter
@Setter
public class SimulationRunEvent {
    @Id
    private UUID id;
    @Column(name = "run_id", nullable = false)
    private UUID runId;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @Column(nullable = false)
    private String message;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
