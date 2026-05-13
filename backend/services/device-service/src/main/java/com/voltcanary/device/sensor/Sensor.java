package com.voltcanary.device.sensor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sensors")
public class Sensor {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    private String unit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
