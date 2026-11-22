package com.voltcanary.aiagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.TextNode;
import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrimarySourceBindingTest {

    @Test
    void simulatorDefaultsUseTheActualExternalSourceIdentity() {
        assertEquals(
            "actual-source",
            PrimaryDomainPreparation.simulatorPayload(Map.of(), "actual-source").get("sourceId")
        );
        assertEquals(
            "SIMULATOR_EXTERNAL_ID_CHANGED",
            assertThrows(IntentPlans.Rejected.class, () ->
                PrimaryDomainPreparation.simulatorPayload(
                    Map.of("sourceId", "other-source"),
                    "actual-source"
                )
            ).getMessage()
        );
    }

    @Test
    void simulatorReviewPinsTheSourceUnderItsDeviceAndRejectsRetargeting() {
        String device = UUID.randomUUID().toString();
        String source = UUID.randomUUID().toString();
        var scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        var initial = new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of());
        var bound = TextNode.valueOf(source);
        var result = PrimaryDomainPreparation.retainSimulatorSource(initial, device, bound, bound);
        var ref = result.refs().getFirst();
        assertEquals("source", ref.kind());
        assertEquals(source, ref.resourceId());
        assertEquals(device, ref.summary().get("deviceId"));
        assertEquals(
            "SIMULATOR_SOURCE_BINDING_CHANGED",
            assertThrows(IntentPlans.Rejected.class, () ->
                PrimaryDomainPreparation.retainSimulatorSource(
                    initial,
                    device,
                    bound,
                    TextNode.valueOf(UUID.randomUUID().toString())
                )
            ).getMessage()
        );
    }

    @Test
    void retainsTheReviewedDeviceForFreshAccessChecksEvenWithoutAnInitialBinding() {
        String id = UUID.randomUUID().toString();
        var scope = new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID());
        var initial = new AuthorizedPlanningContext.Prepared(scope, Map.of(), List.of());
        var reviewed = PrimaryDomainPreparation.retainSourceDevice(initial, id);
        assertEquals(scope, reviewed.scope());
        assertEquals(id, reviewed.refs().getFirst().resourceId());
        assertEquals("device", reviewed.refs().getFirst().kind());
        assertEquals(reviewed, PrimaryDomainPreparation.retainSourceDevice(reviewed, id));
    }

    @Test
    void acceptsTheReceiptDeviceAndRejectsRetargeting() {
        String deviceId = UUID.randomUUID().toString();
        var bound = TextNode.valueOf(deviceId);
        assertEquals(
            deviceId,
            PrimaryDomainPreparation.sourceDevice(
                bound,
                TextNode.valueOf(deviceId.toUpperCase(Locale.ROOT))
            )
        );
        var error = assertThrows(IntentPlans.Rejected.class, () ->
            PrimaryDomainPreparation.sourceDevice(
                bound,
                TextNode.valueOf(UUID.randomUUID().toString())
            )
        );
        assertEquals("SOURCE_DEVICE_BINDING_CHANGED", error.getMessage());
    }
}
