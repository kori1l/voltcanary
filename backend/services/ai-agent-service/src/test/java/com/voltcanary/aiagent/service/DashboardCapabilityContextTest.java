package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.service.AuthorizedPlanningContext.ResourceRef;
import com.voltcanary.common.web.JacksonRestClients;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashboardCapabilityContextTest {
    @Test
    void preservesMissingAndStaleEvidenceInTheModelContext() {
        var fixture = new Fixture("""
                {"snapshotId":"snapshot","choices":[
                  {"choiceId":"missing","deviceId":"DEVICE","sourceId":"SOURCE","key":"doors",
                   "family":"RECORDED_VEHICLE_FIELD","currentStatus":"MISSING","sourceFreshness":"NO_DATA","historyStatus":"UNKNOWN"},
                  {"choiceId":"stale","deviceId":"DEVICE","sourceId":"SOURCE","key":"speed",
                   "family":"RECORDED_VEHICLE_FIELD","currentStatus":"OBSERVED","sourceFreshness":"STALE","historyStatus":"UNKNOWN"}
                ]}
                """);
        fixture.loader.enrich(fixture.context, fixture.refs, List.of(fixture.device),
                Map.of(fixture.device.toString(), "device-ref"), Map.of("sourceId", fixture.source), "Bearer test");

        var choices = (List<?>) fixture.context.get("dashboardDataChoices");
        var missing = (Map<?, ?>) choices.get(0);
        var stale = (Map<?, ?>) choices.get(1);
        assertEquals("MISSING", missing.get("currentStatus"));
        assertEquals("NO_DATA", missing.get("sourceFreshness"));
        assertEquals("UNKNOWN", missing.get("historyStatus"));
        assertEquals("OBSERVED", stale.get("currentStatus"));
        assertEquals("STALE", stale.get("sourceFreshness"));
        assertEquals("UNKNOWN", stale.get("historyStatus"));
        fixture.server.verify();
    }

    @Test
    void newlyConnectedSourceWithoutFieldsRemainsSelected() {
        var fixture = new Fixture();
        fixture.loader.enrich(fixture.context, fixture.refs, List.of(fixture.device),
                Map.of(fixture.device.toString(), "device-ref"), Map.of("sourceId", fixture.source), "Bearer test");

        assertEquals("source-ref", fixture.context.get("selectedDashboardSourceRef"));
        assertEquals(List.of(), fixture.context.get("dashboardDataChoices"));
        fixture.server.verify();
    }

    @Test
    void sourceOwnedByAnotherDeviceIsRejectedEvenWithNoFields() {
        var fixture = new Fixture();
        UUID otherDevice = UUID.randomUUID();
        var rejected = assertThrows(ResponseStatusException.class, () -> fixture.loader.enrich(
                fixture.context, fixture.refs, List.of(otherDevice), Map.of(otherDevice.toString(), "other-device"),
                Map.of("sourceId", fixture.source), "Bearer test"));

        assertEquals(404, rejected.getStatusCode().value());
        fixture.server.verify();
    }

    private static final class Fixture {
        final UUID device = UUID.randomUUID();
        final String source = UUID.randomUUID().toString();
        final Map<String, Object> context = new LinkedHashMap<>();
        final List<ResourceRef> refs = new ArrayList<>(List.of(
                new ResourceRef("source-ref", "source", source, Map.of("deviceId", device.toString()))));
        final MockRestServiceServer server;
        final DashboardCapabilityContext loader;

        Fixture() {
            this("{\"snapshotId\":\"snapshot\",\"choices\":[]}");
        }

        Fixture(String response) {
            var builder = JacksonRestClients.builder().baseUrl("http://dashboard.test");
            server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://dashboard.test/dashboards/composition/capabilities"))
                    .andRespond(withSuccess(response.replace("DEVICE", device.toString()).replace("SOURCE", source),
                            MediaType.APPLICATION_JSON));
            loader = new DashboardCapabilityContext(builder.build());
        }
    }
}
