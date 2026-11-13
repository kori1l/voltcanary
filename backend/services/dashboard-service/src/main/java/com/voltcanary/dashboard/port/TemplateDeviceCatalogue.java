package com.voltcanary.dashboard.port;

import java.util.List;
import java.util.UUID;

/** Authorized capabilities only; never raw source configuration or credentials. */
public interface TemplateDeviceCatalogue {
    record Metric(String name, String unit) { }
    record Source(UUID id, String sourceId) { }
    record Device(UUID id, String name, String type, List<Metric> metrics, List<Source> sources) { }
    Device read(String authorization, UUID id);
}
