package com.voltcanary.device.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "device_type_id", nullable = false)
    private UUID deviceTypeId;

    @Column(nullable = false)
    private String name;

    @Column(name = "external_id")
    private String externalId;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
