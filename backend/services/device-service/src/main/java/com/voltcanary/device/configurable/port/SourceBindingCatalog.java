package com.voltcanary.device.configurable.port;

import com.voltcanary.device.domain.DataSource;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.util.UUID;

public interface SourceBindingCatalog {
    void record(UUID accountId, DataSource source);
    VclSourceBindingDescription describe(UUID actorId, UUID sourceId, Long schemaRevision);
    com.fasterxml.jackson.databind.JsonNode normalization(VclSourceBindingDescription source);
    java.util.Optional<com.voltcanary.common.events.device.SourceBindingSchemaEvent> publication(
        UUID accountId,
        UUID sourceId
    );
}
