package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashboardCapabilityContextTest {
    private final RestClient.Builder builder = com.voltcanary.common.web.JacksonRestClients.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DashboardCapabilityContext context = new DashboardCapabilityContext(builder.baseUrl("http://dashboard").build());
    private final String device = UUID.randomUUID().toString(), source = UUID.randomUUID().toString();
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> choice(String id, String sourceId, String family) {
        var result = new HashMap<String, Object>();
        result.put("choiceId", id);
        result.put("deviceId", device);
        if (sourceId != null) { result.put("sourceId", sourceId); result.put("externalSourceId", sourceId.equals(source) ? "GPS A" : "GPS B"); }
        result.put("family", family);
        result.put("key", "observed.state");
        result.put("unit", "");
        result.put("views", List.of(Map.of("widgetType", "vehicle-timeline", "visualizations", List.of("line", "list"))));
        return result;
    }

    private void response(List<Map<String, Object>> choices, List<Map<String, Object>> diagnostics) throws Exception {
        server.expect(requestTo("http://dashboard/dashboards/composition/capabilities"))
                .andExpect(header("Authorization", "auth"))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("snapshotId", "snapshot", "choices", choices, "diagnostics", diagnostics)), MediaType.APPLICATION_JSON));
    }

    private ArrayList<AuthorizedPlanningContext.ResourceRef> refs() {
        return new ArrayList<>(List.of(new AuthorizedPlanningContext.ResourceRef("older-device", "device", device, Map.of()),
                new AuthorizedPlanningContext.ResourceRef("current-device", "device", device, Map.of()),
                new AuthorizedPlanningContext.ResourceRef("source-ref", "source", source, Map.of())));
    }

    @Test void refsAreShortRenewedAndResolveAgainstTheCurrentDeviceRef() throws Exception {
        response(List.of(choice("identity", source, "RECORDED_VEHICLE_FIELD")), List.of());
        response(List.of(choice("identity", source, "RECORDED_VEHICLE_FIELD")), List.of());
        var first = new HashMap<String, Object>();
        var firstRefs = refs();
        context.enrich(first, firstRefs, List.of(UUID.fromString(device)), Map.of(device, "current-device"), Map.of(), "auth");
        var second = new HashMap<String, Object>();
        context.enrich(second, refs(), List.of(UUID.fromString(device)), Map.of(device, "current-device"), Map.of(), "auth");
        var firstChoice = (Map<?, ?>) ((List<?>) first.get("dashboardDataChoices")).getFirst();
        var secondChoice = (Map<?, ?>) ((List<?>) second.get("dashboardDataChoices")).getFirst();
        assertThat(String.valueOf(firstChoice.get("dataRef"))).startsWith("d1_").contains("_observed_sta_")
                .hasSizeLessThan(40).isNotEqualTo(secondChoice.get("dataRef"));
        assertThat(first.toString()).doesNotContain(device, source);
        var prepared = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID()), first, firstRefs);
        var panel = DashboardDataReferences.resolve(Map.of("type", "vehicle-timeline", "dataRef", firstChoice.get("dataRef")), prepared);
        assertThat(panel.get("deviceRefs")).isEqualTo(List.of("current-device"));
        server.verify();
    }

    @Test void explicitSourceDoesNotSelectUnboundAggregatedMetricsOrOtherSources() throws Exception {
        String other = UUID.randomUUID().toString();
        response(List.of(choice("a", source, "RECORDED_VEHICLE_FIELD"), choice("b", other, "RECORDED_VEHICLE_FIELD"),
                choice("metric", null, "REGISTERED_NUMERIC_METRIC")), List.of(Map.of("code", "AMBIGUOUS_SOURCE", "choiceIds", List.of("a", "b"))));
        var result = new HashMap<String, Object>();
        var refs = refs();
        refs.add(new AuthorizedPlanningContext.ResourceRef("other-source", "source", other, Map.of()));
        context.enrich(result, refs, List.of(UUID.fromString(device)), Map.of(device, "current-device"), Map.of("sourceId", other), "auth");
        assertThat((List<?>) result.get("dashboardDataChoices")).singleElement().satisfies(item -> {
            var selected = (Map<?, ?>) item;
            assertThat(selected.get("sourceLabel")).isEqualTo("GPS B");
            assertThat(selected.get("sourceChoiceLabel")).isEqualTo("Источник 2");
            assertThat(selected.get("requiresSourceChoice")).isEqualTo(false);
        });
        assertThat(result.get("selectedDashboardSourceRef")).isEqualTo("other-source");
    }

    @Test void choosingOneVehiclesSourceLeavesOtherVehiclesChoicesIntact() throws Exception {
        String otherDevice = UUID.randomUUID().toString(), b1 = UUID.randomUUID().toString(), b2 = UUID.randomUUID().toString();
        var first = new HashMap<>(choice("b1", b1, "RECORDED_VEHICLE_FIELD"));
        first.put("deviceId", otherDevice);
        first.put("externalSourceId", "B1");
        var second = new HashMap<>(choice("b2", b2, "RECORDED_VEHICLE_FIELD"));
        second.put("deviceId", otherDevice);
        second.put("externalSourceId", "B2");
        response(List.of(choice("a", source, "RECORDED_VEHICLE_FIELD"), first, second),
                List.of(Map.of("code", "AMBIGUOUS_SOURCE", "choiceIds", List.of("b1", "b2"))));
        var result = new HashMap<String, Object>();
        var refs = refs();
        refs.add(new AuthorizedPlanningContext.ResourceRef("device-b", "device", otherDevice, Map.of()));
        refs.add(new AuthorizedPlanningContext.ResourceRef("source-b1", "source", b1, Map.of()));
        refs.add(new AuthorizedPlanningContext.ResourceRef("source-b2", "source", b2, Map.of()));
        context.enrich(result, refs, List.of(UUID.fromString(device), UUID.fromString(otherDevice)), Map.of(device, "current-device", otherDevice, "device-b"),
                Map.of("sourceSelections", Map.of(device, source)), "auth");
        var choices = (List<?>) result.get("dashboardDataChoices");
        assertThat(choices).hasSize(3);
        assertThat(choices).filteredOn(item -> "device-b".equals(((Map<?, ?>) item).get("deviceRef")))
                .allSatisfy(item -> assertThat(((Map<?, ?>) item).get("requiresSourceChoice")).isEqualTo(true));
        assertThat(result).doesNotContainKey("selectedDashboardSourceRef");
    }
}
