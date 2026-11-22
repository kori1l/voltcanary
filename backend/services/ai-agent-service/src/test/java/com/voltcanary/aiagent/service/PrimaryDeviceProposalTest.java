package com.voltcanary.aiagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.voltcanary.aiagent.orchestration.plan.IntentPlans;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrimaryDeviceProposalTest {

    private final String locationRef = UUID.randomUUID().toString();
    private final String locationId = UUID.randomUUID().toString();
    private final List<AuthorizedPlanningContext.ResourceRef> catalogue = List.of(
        new AuthorizedPlanningContext.ResourceRef("custom", "type", "CUSTOM_SENSOR", Map.of()),
        new AuthorizedPlanningContext.ResourceRef(locationRef, "location", locationId, Map.of())
    );

    @Test
    void resolvesCatalogueLocationAndPreservesOptionalMetadata() {
        var proposal = Map.<String, Object>of(
            "name",
            "Probe",
            "type",
            "CUSTOM_SENSOR",
            "locationId",
            locationRef,
            "manufacturer",
            "Acme",
            "model",
            "A-1"
        );
        assertEquals(
            Map.of(
                "name",
                "Probe",
                "type",
                "CUSTOM_SENSOR",
                "locationId",
                locationId,
                "manufacturer",
                "Acme",
                "model",
                "A-1"
            ),
            new PrimaryDeviceProposal(proposal, catalogue).reviewPayload(null)
        );
    }

    @Test
    void acceptsAnInstalledCustomTypeWithOnlyRequiredFields() {
        var proposal = Map.<String, Object>of("name", "Probe", "type", "CUSTOM_SENSOR");
        assertEquals(proposal, new PrimaryDeviceProposal(proposal, catalogue).reviewPayload(null));
    }

    @Test
    void retainsTheAuthorizedSelectionWhenTheModelOmitsTheLocation() {
        var proposal = Map.<String, Object>of("name", "Probe", "type", "CUSTOM_SENSOR");
        assertEquals(
            locationId,
            new PrimaryDeviceProposal(proposal, catalogue)
                .reviewPayload(locationId)
                .get("locationId")
        );
    }

    @Test
    void rejectsReplacingAnExplicitSelectionWithAnotherAuthorizedLocation() {
        String selectedId = UUID.randomUUID().toString();
        var available = new java.util.ArrayList<>(catalogue);
        available.add(
            new AuthorizedPlanningContext.ResourceRef("selected", "location", selectedId, Map.of())
        );
        var proposal = Map.<String, Object>of(
            "name",
            "Probe",
            "type",
            "CUSTOM_SENSOR",
            "locationId",
            locationRef
        );
        assertEquals(
            "DEVICE_LOCATION_BINDING_CHANGED",
            assertThrows(IntentPlans.Rejected.class, () ->
                new PrimaryDeviceProposal(proposal, available).reviewPayload(selectedId)
            ).getMessage()
        );
    }

    @Test
    void rejectsUnknownChoicesAndFieldsThatWouldFailAtTheOwner() {
        assertRejected(
            "DEVICE_LOCATION_UNAVAILABLE",
            Map.of(
                "name",
                "Probe",
                "type",
                "CUSTOM_SENSOR",
                "locationId",
                UUID.randomUUID().toString()
            )
        );
        assertRejected("DEVICE_TYPE_UNAVAILABLE", Map.of("name", "Probe", "type", "UNKNOWN"));
        assertRejected("DEVICE_NAME_INVALID", Map.of("name", " ", "type", "CUSTOM_SENSOR"));
        assertRejected(
            "DEVICE_OPTIONAL_TEXT_INVALID",
            Map.of("name", "Probe", "type", "CUSTOM_SENSOR", "model", "x".repeat(256))
        );
    }

    private void assertRejected(String code, Map<String, Object> proposal) {
        assertEquals(
            code,
            assertThrows(IntentPlans.Rejected.class, () ->
                new PrimaryDeviceProposal(proposal, catalogue).reviewPayload(null)
            ).getMessage()
        );
    }
}
