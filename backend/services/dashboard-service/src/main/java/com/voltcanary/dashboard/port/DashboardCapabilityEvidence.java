package com.voltcanary.dashboard.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Authorized metadata only; observation values and connector configuration never cross this port. */
public interface DashboardCapabilityEvidence {
    record Field(String key, String label, String valueType, String unit, String sourceId,
            boolean observed, Instant recordedAt) { }
    record Source(UUID id, String externalId, String freshness) { }
    record Device(UUID id, String label, String type, List<TemplateDeviceCatalogue.Metric> metrics,
            List<Source> sources, List<Field> fields) { }
    Device read(String authorization, UUID deviceId);
}
