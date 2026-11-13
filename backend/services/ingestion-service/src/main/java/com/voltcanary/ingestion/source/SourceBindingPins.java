package com.voltcanary.ingestion.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.SourceBindingSchemaPin;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reads only source-resolved metadata. Incoming metrics/context never supply these pins. */
public final class SourceBindingPins {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SourceBindingPins() { }

    public static SourceBindingSchemaPin pin(ResolvedSource source) {
        if (!source.mapping().containsKey("bindingSchemaPin")) return null;
        try {
            Object raw = source.mapping().get("bindingSchemaPin");
            if (!(raw instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("bindingId", "accountId", "deviceId",
                    "externalSourceId", "schemaRevision", "snapshotId", "definitionDigest"))) throw new IllegalArgumentException();
            Object revision = map.get("schemaRevision");
            if (!(revision instanceof Byte || revision instanceof Short || revision instanceof Integer || revision instanceof Long)) throw new IllegalArgumentException();
            var pin = MAPPER.convertValue(raw, SourceBindingSchemaPin.class);
            if (!pin.bindingId().equals(source.dataSourceId()) || !pin.accountId().equals(source.accountId())
                    || !pin.deviceId().equals(source.deviceId()) || !pin.externalSourceId().equals(source.externalId())) throw new IllegalArgumentException();
            return pin;
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Source binding schema is unavailable");
        }
    }
}
