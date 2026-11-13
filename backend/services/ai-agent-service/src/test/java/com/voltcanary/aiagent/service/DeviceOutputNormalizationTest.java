package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.llm.LlmActionDraft;
import com.voltcanary.aiagent.llm.LlmPlanDraft;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceOutputNormalizationTest {
    @Test void typeRefAliasUsesOnlyAnUnambiguousCurrentCatalogueEntry() {
        var context = Map.<String, Object>of("deviceTypes", List.of(Map.of("ref", "current-type", "code", "SENSOR")));
        assertThat(DeviceOutputNormalization.normalize(draft(Map.of("typeRef", "current-type")), context)
                .actions().getFirst().parameters()).containsExactlyEntriesOf(Map.of("deviceTypeRef", "current-type"));
        assertThat(DeviceOutputNormalization.normalize(draft(Map.of("typeRef", "SENSOR")), context)
                .actions().getFirst().parameters()).containsExactlyEntriesOf(Map.of("type", "SENSOR"));
        var unknown = draft(Map.of("typeRef", "expired-or-invented-ref"));
        assertThat(DeviceOutputNormalization.normalize(unknown, context)).isEqualTo(unknown);
        var conflict = draft(Map.of("typeRef", "current-type", "deviceTypeRef", "other"));
        assertThat(DeviceOutputNormalization.normalize(conflict, context)).isEqualTo(conflict);
        var ambiguous = Map.<String, Object>of("deviceTypes", List.of(Map.of("ref", "duplicate", "code", "SENSOR"), Map.of("ref", "duplicate", "code", "FRIDGE")));
        var duplicate = draft(Map.of("typeRef", "duplicate"));
        assertThat(DeviceOutputNormalization.normalize(duplicate, ambiguous)).isEqualTo(duplicate);
    }
    @Test
    void normalizesOnlyUniqueEquivalentAliasesAndPreservesKnownFactsForCompiler() {
        var draft = draft(Map.of("deviceName", "bus22", "deviceType", "VEHICLE"));
        var normalized = DeviceOutputNormalization.normalize(draft, Map.of("name", "bus23"));
        assertThat(normalized.actions().getFirst().parameters()).containsExactlyInAnyOrderEntriesOf(Map.of("name", "bus22", "type", "VEHICLE"));
        // The compiler still gives authoritative context.name precedence over this candidate name.
        var conflict = draft(Map.of("name", "bus23", "deviceName", "bus22"));
        assertThat(DeviceOutputNormalization.normalize(conflict, Map.of())).isEqualTo(conflict);
    }

    @Test
    void acceptsOnlyExactRedundantFixedHslHintAndRetainsUnsafeOrUnknownFieldsForRejection() {
        String original = "Создай автобус bus22 и подключи HSL 0012-02512 bus MQTT";
        var context = Map.<String, Object>of("originalDeviceRequest", original, "deviceSetupCapabilities", List.of("HSL_HFP"));
        var normalized = DeviceOutputNormalization.normalize(draft(Map.of("name", "bus22", "connection",
                Map.of("provider", "HSL_HFP", "mode", "bus", "vehicleKey", "0012-02512"))), context);
        assertThat(normalized.actions().getFirst().parameters()).containsOnlyKeys("name");
        var unsafe = draft(Map.of("name", "bus22", "connection", Map.of("url", "mqtt://untrusted.example"), "apiKey", "fixture"));
        assertThat(DeviceOutputNormalization.normalize(unsafe, context)).isEqualTo(unsafe);
        var differentVehicle = draft(Map.of("connection", Map.of("provider", "HSL_HFP", "mode", "bus", "vehicleKey", "0012-99999")));
        assertThat(DeviceOutputNormalization.normalize(differentVehicle, context)).isEqualTo(differentVehicle);
    }

    private LlmPlanDraft draft(Map<String, Object> parameters) {
        return new LlmPlanDraft("fixture", "fixture", "summary", "explanation", List.of(new LlmActionDraft("CREATE_DEVICE", "title", "description", parameters)));
    }
}
