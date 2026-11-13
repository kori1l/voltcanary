package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltcanary.aiagent.model.AgentTool;
import com.voltcanary.aiagent.orchestration.contract.ProtocolValidator;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.service.PrimarySkillPackages;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.voltcanary.aiagent.nativeops.NativeAuthoringFixtures.JSON;
import static com.voltcanary.aiagent.nativeops.NativeAuthoringFixtures.FIXTURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCollectionDraftTest {
    private static final ProtocolValidator VALIDATOR = new ProtocolValidator(JSON);

    @Test void allSevenTypesProduceTheSameCanonicalDocumentAsTheManualUiFixture() {
        var draft = draft();
        var fields = new LinkedHashMap<String, UUID>();
        FIXTURE.at("/reservation/fieldIdsByKey").fields().forEachRemaining(entry -> fields.put(entry.getKey(), UUID.fromString(entry.getValue().asText())));
        assertEquals(FIXTURE.path("document"), draft.document(NativeAuthoringFixtures.id("resourceId"), fields, JSON).json());
        assertEquals(7, draft.fieldKeys().size());
    }

    @ParameterizedTest @ValueSource(strings = {"sql", "accountId", "actorId", "approved", "ownerReviewId", "reservationId", "document"})
    void authoritySqlAndExecutableEnvelopeFieldsAreRejected(String field) {
        var value = (ObjectNode) FIXTURE.path("draft").deepCopy();
        value.put(field, "untrusted");
        assertThrows(IllegalArgumentException.class, () -> new NativeCollectionDraft(value, VALIDATOR, JSON));
    }

    @ParameterizedTest @ValueSource(strings = {"REF", "POINT", "INTERVAL", "ENUM", "SQL", "CUSTOM"})
    void unimplementedAuthoringTypesCannotReachTheOwner(String type) {
        var value = (ObjectNode) FIXTURE.path("draft").deepCopy();
        ((ObjectNode) value.path("fields").get(0)).put("type", type);
        assertThrows(IllegalArgumentException.class, () -> new NativeCollectionDraft(value, VALIDATOR, JSON));
    }

    @Test void duplicateKeysNullableKeysAndIncompatibleUnitsAreRejected() {
        var value = (ObjectNode) FIXTURE.path("draft").deepCopy();
        ((ObjectNode) value.path("fields").get(1)).put("key", "code");
        var duplicate = value;
        assertThrows(IllegalArgumentException.class, () -> new NativeCollectionDraft(duplicate, VALIDATOR, JSON));
        value = (ObjectNode) FIXTURE.path("draft").deepCopy();
        ((ObjectNode) value.path("fields").get(0)).put("nullable", true);
        var nullable = value;
        assertThrows(IllegalArgumentException.class, () -> new NativeCollectionDraft(nullable, VALIDATOR, JSON));
        for (String unit : List.of("V", "unregistered")) {
            var invalid = (ObjectNode) FIXTURE.path("draft").deepCopy();
            ((ObjectNode) invalid.path("fields").get(1)).put("unit", unit);
            assertThrows(IllegalArgumentException.class, () -> new NativeCollectionDraft(invalid, VALIDATOR, JSON));
        }
    }

    @Test void reservedFieldIdentityIsExactAndDefensivelyCopied() {
        var source = (ObjectNode) FIXTURE.path("draft").deepCopy();
        var draft = new NativeCollectionDraft(source, VALIDATOR, JSON);
        source.put("label", "Changed");
        assertEquals("Service visits", draft.value().path("label").asText());
        ((ObjectNode) draft.value()).put("label", "Another change");
        assertEquals("Service visits", draft.value().path("label").asText());
        assertThrows(IllegalArgumentException.class, () -> draft.document(UUID.randomUUID(), Map.of("foreign", UUID.randomUUID()), JSON));
        assertThrows(UnsupportedOperationException.class, () -> draft.fieldKeys().clear());
    }

    @Test void disabledNativePackageLeavesLegacyPolicyFingerprintAndCatalogueUnchanged() {
        var all = PrimarySkillPackages.build(JSON, VALIDATOR, List.of());
        var previous = all.stream().filter(pkg -> !pkg.manifest().path("intentId").asText().equals(NativeTableSkill.INTENT)).toList();
        var registry = new SkillRegistry(VALIDATOR, all);
        var legacy = new SkillRegistry(VALIDATOR, previous);
        var names = new java.util.HashSet<String>();
        for (AgentTool tool : AgentTool.values()) names.add(tool.name());
        names.add("REFINE_DASHBOARD_REVIEW");
        assertEquals(legacy.versionDigest(), registry.versionDigest(names, Set.of()));
        assertFalse(registry.catalogue(names, Set.of()).stream().anyMatch(intent -> intent.intentId().equals(NativeTableSkill.INTENT)));
        assertTrue(registry.catalogue(names, Set.of(NativeTableSkill.CAPABILITY)).stream().anyMatch(intent -> intent.intentId().equals(NativeTableSkill.INTENT)));
        assertThrows(IllegalArgumentException.class, () -> registry.manifest(NativeTableSkill.INTENT, names, Set.of()));
    }

    private static NativeCollectionDraft draft() { return new NativeCollectionDraft(FIXTURE.path("draft"), VALIDATOR, JSON); }
}
