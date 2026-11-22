package com.voltcanary.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.voltcanary.ingestion.source.ResolvedSource;
import com.voltcanary.ingestion.source.SourceBindingPins;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SourceBindingPinsTest {

    private static final UUID ACCOUNT = UUID.randomUUID(),
        DEVICE = UUID.randomUUID(),
        SOURCE = UUID.randomUUID(),
        SNAPSHOT = UUID.randomUUID();

    @Test
    void exactOwnerIdentityIsRequiredAndLegacySourcesHaveNoSyntheticPin() {
        assertNull(SourceBindingPins.pin(source(Map.of())));
        var pin = SourceBindingPins.pin(source(Map.of("bindingSchemaPin", pin())));
        assertEquals(SNAPSHOT, pin.snapshotId());
        assertEquals(SOURCE, pin.bindingId());
        for (String field : java.util.List.of(
            "accountId",
            "deviceId",
            "bindingId",
            "externalSourceId"
        )) {
            var changed = pin();
            changed.put(
                field,
                field.equals("externalSourceId") ? "other-source" : UUID.randomUUID().toString()
            );
            invalid(changed);
        }
    }

    @Test
    void fractionalCoercedMissingOrAuthorityAmplifyingPinsFailClosed() {
        for (Object revision : java.util.List.of(1.5, 1.0, "1", 0, -1, 9_007_199_254_740_992L)) {
            var changed = pin();
            changed.put("schemaRevision", revision);
            invalid(changed);
        }
        var missing = pin();
        missing.remove("snapshotId");
        invalid(missing);
        var extra = pin();
        extra.put("ruleExecutionReady", true);
        invalid(extra);
        var corrupt = pin();
        corrupt.put("definitionDigest", "private-invalid-digest");
        invalid(corrupt);
    }

    private static void invalid(Map<String, Object> changed) {
        var failure = assertThrows(ResponseStatusException.class, () ->
            SourceBindingPins.pin(source(Map.of("bindingSchemaPin", changed)))
        );
        assertEquals(503, failure.getStatusCode().value());
        assertEquals("Source binding schema is unavailable", failure.getReason());
    }

    private static ResolvedSource source(Map<String, Object> mapping) {
        return new ResolvedSource(
            ACCOUNT,
            DEVICE,
            SOURCE,
            "source",
            "SENSOR",
            "REST",
            "ACTIVE",
            mapping
        );
    }

    private static Map<String, Object> pin() {
        return new HashMap<>(
            Map.of(
                "bindingId",
                SOURCE.toString(),
                "accountId",
                ACCOUNT.toString(),
                "deviceId",
                DEVICE.toString(),
                "externalSourceId",
                "source",
                "schemaRevision",
                1,
                "snapshotId",
                SNAPSHOT.toString(),
                "definitionDigest",
                "a".repeat(64)
            )
        );
    }
}
