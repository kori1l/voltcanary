package com.voltcanary.device.metrics;

import com.voltcanary.device.datasource.DataSourceProtocol;
import com.voltcanary.device.datasource.DataSourceRepository;
import com.voltcanary.device.datasource.SourceStatus;
import com.voltcanary.device.device.DeviceRepository;
import com.voltcanary.device.device.DeviceStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeviceOperationalMetrics {

    private final MeterRegistry meterRegistry;
    private final DeviceRepository deviceRepository;
    private final DataSourceRepository dataSourceRepository;

    public DeviceOperationalMetrics(
            MeterRegistry meterRegistry,
            DeviceRepository deviceRepository,
            DataSourceRepository dataSourceRepository) {
        this.meterRegistry = meterRegistry;
        this.deviceRepository = deviceRepository;
        this.dataSourceRepository = dataSourceRepository;
    }

    @PostConstruct
    void bindGauges() {
        for (DeviceStatus status : DeviceStatus.values()) {
            meterRegistry.gauge(
                    "voltcanary.device.devices.count",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("status", status.name())),
                    deviceRepository,
                    repository -> repository.countByStatus(status));
        }
        seededDeviceTypes().forEach((code, id) -> meterRegistry.gauge(
                "voltcanary.device.devices.by_type.count",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("type", code)),
                deviceRepository,
                repository -> repository.countByDeviceTypeId(id)));
        for (DataSourceProtocol protocol : DataSourceProtocol.values()) {
            meterRegistry.gauge(
                    "voltcanary.device.data_sources.by_protocol.count",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("protocol", protocol.name())),
                    dataSourceRepository,
                    repository -> repository.countByProtocol(protocol));
        }
        for (SourceStatus status : SourceStatus.values()) {
            meterRegistry.gauge(
                    "voltcanary.device.data_sources.count",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("status", status.name())),
                    dataSourceRepository,
                    repository -> repository.countByStatus(status));
        }
    }

    public Timer.Sample startInternalValidation() {
        return Timer.start(meterRegistry);
    }

    public void stopInternalValidation(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("voltcanary.device.internal_validation.latency")
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    public void ownershipRejected() {
        meterRegistry.counter("voltcanary.device.ownership.rejected").increment();
    }

    public void operationRejected(String reason) {
        meterRegistry.counter("voltcanary.device.operation.rejected", "reason", reason).increment();
    }

    private Map<String, UUID> seededDeviceTypes() {
        return Map.ofEntries(
                Map.entry("GENERIC_APPLIANCE", UUID.fromString("00000000-0000-0000-0000-000000000001")),
                Map.entry("AIR_CONDITIONER", UUID.fromString("00000000-0000-0000-0000-000000000002")),
                Map.entry("FRIDGE", UUID.fromString("00000000-0000-0000-0000-000000000003")),
                Map.entry("BOILER", UUID.fromString("00000000-0000-0000-0000-000000000004")),
                Map.entry("PUMP", UUID.fromString("00000000-0000-0000-0000-000000000005")),
                Map.entry("HEATER", UUID.fromString("00000000-0000-0000-0000-000000000006")),
                Map.entry("WASHING_MACHINE", UUID.fromString("00000000-0000-0000-0000-000000000007")),
                Map.entry("SERVER_NAS", UUID.fromString("00000000-0000-0000-0000-000000000008")),
                Map.entry("THREE_D_PRINTER", UUID.fromString("00000000-0000-0000-0000-000000000009")),
                Map.entry("CUSTOM", UUID.fromString("00000000-0000-0000-0000-000000000010")));
    }
}
