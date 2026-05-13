package com.voltcanary.device.datasource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_sources")
public class DataSource {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Column(name = "connection_ref")
    private String connectionRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
