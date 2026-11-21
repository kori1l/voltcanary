package com.voltcanary.device.domain;

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
@org.hibernate.annotations.SQLRestriction("deleted_at is null")
@Table(name = "devices")
@Getter
@Setter
public class Device {
    @Column(name = "deleted_at")
    private Instant deletedAt;

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

    private String manufacturer;

    private String model;

    @Column(name = "metadata_json")
    private String metadataJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
