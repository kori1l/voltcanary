package com.voltcanary.device.devicetype;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "device_types")
public class DeviceType {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String displayName;
}
