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
@Table(name = "ml_external_device_mappings")
@Getter
@Setter
public class MlExternalDeviceMapping {
    @Id
    private UUID id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "imported_file_id", nullable = false)
    private UUID importedFileId;
    @Column(name = "external_device_key", nullable = false)
    private String externalDeviceKey;
    @Column(name = "matched_device_id")
    private UUID matchedDeviceId;
    @Column(name = "virtual_device_key", nullable = false)
    private String virtualDeviceKey;
    @Column(name = "device_type")
    private String deviceType;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
