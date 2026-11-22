package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class DashboardPlanningContextTest {

    @Test
    void fullStructuredOwnerCatalogueDoesNotConsumeUntrustedContextBudget() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var catalogue = mapper.createArrayNode();
        for (int widget = 0; widget < 18; widget++) {
            var item = catalogue
                .addObject()
                .put("type", "widget-" + widget)
                .put("title", "Registered widget");
            item.putArray("visualizations");
            item.putArray("reducers");
            var capability = item.putObject("capabilities").put("version", 1);
            capability.putArray("valueTypes").add("number");
            capability.putObject("visualizationValueTypes");
            capability.putObject("binding").putArray("modes").add("FIXED");
            var settings = capability.putArray("settings");
            var schema = capability
                .putObject("settingsSchema")
                .put("type", "object")
                .putObject("properties");
            for (int key = 0; key < 24; key++) {
                settings
                    .addObject()
                    .put("key", "option" + key)
                    .put("type", "BOOLEAN")
                    .put("label", "Supported option")
                    .put("defaultValue", false)
                    .putArray("choices");
                schema
                    .putObject("option" + key)
                    .put("description", "Supported option")
                    .putArray("type")
                    .add("boolean")
                    .add("null");
            }
        }
        server
            .expect(requestTo("http://dashboards/dashboard-templates"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("http://dashboards/dashboards/composition/widget-types"))
            .andRespond(withSuccess(catalogue.toString(), MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("http://devices/devices?status=ACTIVE&limit=100&offset=0"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        var prepared = context().enrich(base, Map.of(), "Bearer current");
        assertThat((List<?>) prepared.context().get("dashboardWidgetTypes")).hasSize(18);
        server.verify();
    }

    private final RestClient.Builder builder =
        com.voltcanary.common.web.JacksonRestClients.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AiAgentProperties properties = new AiAgentProperties();
    private final AuthorizedPlanningContext.Prepared base = new AuthorizedPlanningContext.Prepared(
        new AuthorizedPlanningContext.Scope(UUID.randomUUID(), UUID.randomUUID()),
        Map.of(
            "sourceId",
            "forged-source",
            "yamlConfig",
            "never-forward-yaml",
            "accountId",
            "forged-account"
        ),
        List.of()
    );

    private DashboardPlanningContext context() {
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        properties.getServices().setDeviceBaseUrl("http://devices");
        properties.getServices().setTelemetryBaseUrl("http://telemetry");
        return new DashboardPlanningContext(properties, builder, new PlanningInputGuard());
    }

    private void templates() {
        server
            .expect(requestTo("http://dashboards/dashboard-templates"))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(
                withSuccess(
                    "[{\"id\":\"my-devices\",\"title\":\"My devices\",\"version\":1,\"available\":true,\"period\":\"LAST_24_HOURS\",\"panels\":[]}]",
                    MediaType.APPLICATION_JSON
                )
            );
        server
            .expect(requestTo("http://dashboards/dashboards/composition/widget-types"))
            .andRespond(
                withSuccess(
                    """
                    [{"type":"vehicle-map","title":"Vehicle movement map","description":"Recorded GPS movement",
                      "typeVersion":1,"maxRows":50,"timeBasis":"SNAPSHOT_AND_INTERVAL","visualizations":[],"reducers":[],
                      "capabilities":{"version":1,"valueTypes":[],"visualizationValueTypes":{},"binding":{"modes":[]},
                        "settings":[{"key":"showMapNotices","type":"BOOLEAN","label":"Show stale data notices","defaultValue":true,"choices":[]}],
                        "settingsSchema":{"type":"object","properties":{"showMapNotices":{"type":["boolean","null"],"default":true}}}}}]
                    """,
                    MediaType.APPLICATION_JSON
                )
            );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void onlyCurrentDomainSummariesAndOpaqueRefsReachDashboardPlanning(boolean operations) {
        properties.setDashboardOperationsEnabled(operations);
        UUID device = UUID.randomUUID(),
            source = UUID.randomUUID();
        templates();
        server
            .expect(requestTo("http://devices/devices/" + device))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(
                withSuccess(
                    "{\"id\":\"" +
                        device +
                        "\",\"name\":\"Owned fridge\",\"type\":\"FRIDGE\",\"metadata\":{\"private\":\"never-forward-device-metadata\"}}",
                    MediaType.APPLICATION_JSON
                )
            );
        server
            .expect(requestTo("http://devices/devices/" + device + "/data-sources"))
            .andRespond(
                withSuccess(
                    "[{\"id\":\"" +
                        source +
                        "\",\"sourceType\":\"SENSOR\",\"credentialReferences\":{\"password\":\"never-forward-source-credential\"}}]",
                    MediaType.APPLICATION_JSON
                )
            );
        server
            .expect(requestTo("http://dashboards/dashboards/composition/capabilities"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(
                withSuccess(
                    "{\"snapshotId\":\"current\",\"choices\":[{\"choiceId\":\"power\",\"deviceId\":\"" +
                        device +
                        "\",\"family\":\"REGISTERED_NUMERIC_METRIC\",\"key\":\"activePowerW\",\"label\":\"Power\",\"valueType\":\"number\",\"unit\":\"W\",\"views\":[]}],\"diagnostics\":[]}",
                    MediaType.APPLICATION_JSON
                )
            );
        var inherited = new AuthorizedPlanningContext.Prepared(
            base.scope(),
            base.context(),
            List.of(
                new AuthorizedPlanningContext.ResourceRef(
                    "older-device-ref",
                    "device",
                    device.toString(),
                    Map.of("name", "Owned fridge")
                )
            )
        );
        var prepared = context().enrich(
            inherited,
            Map.of(
                "deviceIds",
                List.of(device.toString()),
                "previousDashboardIntent",
                Map.of(
                    "widgets",
                    List.of(Map.of("key", "map", "deviceIds", List.of(device.toString())))
                )
            ),
            "Bearer current"
        );
        assertThat(prepared.context().toString())
            .contains("Owned fridge", "activePowerW")
            .doesNotContain(
                "forged-account",
                "forged-source",
                "never-forward",
                "credentialReferences",
                "yamlConfig"
            );
        var widget = new com.fasterxml.jackson.databind.ObjectMapper()
            .valueToTree(prepared.context())
            .at("/dashboardWidgetTypes/0");
        assertThat(widget.path("description").asText()).isEqualTo("Recorded GPS movement");
        assertThat(widget.path("maxRows").asInt()).isEqualTo(50);
        assertThat(widget.at("/capabilities/settingContracts/0/label").asText()).isEqualTo(
            "Show stale data notices"
        );
        assertThat(
            widget.at("/capabilities/settingsSchema/properties/showMapNotices/default").asBoolean()
        ).isTrue();
        if (operations) assertThat(prepared.context()).doesNotContainKey("dashboardTemplates");
        else assertThat(prepared.context().toString()).contains("My devices");
        assertThat(prepared.refs()).hasSize(operations ? 7 : 6);
        String selectedRef = (String) (
            (List<?>) prepared.context().get("selectedDashboardDeviceRefs")
        ).getFirst();
        var previous = (Map<?, ?>) prepared.context().get("previousDashboardIntent");
        var restoredWidget = (Map<?, ?>) ((List<?>) previous.get("widgets")).getFirst();
        assertThat(restoredWidget.get("deviceRefs")).isEqualTo(List.of(selectedRef));
        assertThat(selectedRef).isNotEqualTo("older-device-ref");
        assertThat(prepared.context().get("selectedDashboardDeviceRefs")).isEqualTo(
            List.of(selectedRef)
        );
        assertThat(
            prepared
                .refs()
                .stream()
                .filter(ref -> ref.kind().equals("source"))
                .findFirst()
                .orElseThrow()
                .resourceId()
        ).isEqualTo(source.toString());
        server.verify();
    }

    @Test
    void catalogueCandidatesDoNotBecomeAnImplicitEquipmentSelection() {
        UUID device = UUID.randomUUID();
        templates();
        server
            .expect(requestTo("http://devices/devices?status=ACTIVE&limit=100&offset=0"))
            .andRespond(
                withSuccess(
                    "[{\"id\":\"" + device + "\",\"name\":\"Candidate\",\"type\":\"FRIDGE\"}]",
                    MediaType.APPLICATION_JSON
                )
            );
        var prepared = context().enrich(base, Map.of(), "Bearer current");
        assertThat(prepared.context().get("selectedDashboardDeviceRefs")).isEqualTo(List.of());
        assertThat(prepared.refs()).anyMatch(
            ref -> ref.kind().equals("device") && ref.resourceId().equals(device.toString())
        );
        server.verify();
    }

    @Test
    void foreignSelectionFailsBeforeAnyModelContextCanBeReturned() {
        templates();
        UUID foreign = UUID.randomUUID();
        server
            .expect(requestTo("http://devices/devices/" + foreign))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() ->
            context().enrich(
                base,
                Map.of("deviceIds", List.of(foreign.toString())),
                "Bearer current"
            )
        ).isInstanceOfSatisfying(ResponseStatusException.class, failure ->
            assertThat(failure.getStatusCode().value()).isEqualTo(404)
        );
        server.verify();
    }

    @Test
    void pluralSelectionIsBoundedAndDuplicatesAreNotSilentlyRemoved() {
        templates();
        UUID device = UUID.randomUUID();
        assertThatThrownBy(() ->
            context().enrich(
                base,
                Map.of("deviceIds", List.of(device.toString(), device.toString())),
                "Bearer current"
            )
        ).isInstanceOfSatisfying(ResponseStatusException.class, failure ->
            assertThat(failure.getStatusCode().value()).isEqualTo(422)
        );
        server.verify();
    }
}
