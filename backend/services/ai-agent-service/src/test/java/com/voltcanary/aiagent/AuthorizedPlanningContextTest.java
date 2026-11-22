package com.voltcanary.aiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.AuthorizedPlanningContext;
import com.voltcanary.aiagent.service.PlanningInputGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class AuthorizedPlanningContextTest {

    @Test
    void hslCapabilityRequiresBothTheEnabledFlagAndAnInstalledAdapter() {
        var properties = new AiAgentProperties();
        var connector = org.mockito.Mockito.mock(
            com.voltcanary.aiagent.port.DeviceIntegration.class
        );
        org.mockito.Mockito.when(connector.provider()).thenReturn("HSL_HFP");
        var installed = new AuthorizedPlanningContext(
            properties,
            builder.clone(),
            guard,
            List.of(connector)
        );
        var absent = new AuthorizedPlanningContext(properties, builder.clone(), guard, List.of());
        properties.setDeviceSetupEnabled(false);
        assertThat(installed.hslSourceAvailable()).isFalse();
        properties.setDeviceSetupEnabled(true);
        assertThat(absent.hslSourceAvailable()).isFalse();
        assertThat(installed.hslSourceAvailable()).isTrue();
    }

    @Test
    void widgetDescriptorsRenewThroughTheirOwningCatalogueAndRejectChangedSettings() {
        var ref = new AuthorizedPlanningContext.ResourceRef(
            "widget-value",
            "widget-descriptor",
            "value",
            Map.of("settings", List.of())
        );
        server
            .expect(requestTo("http://dashboards/dashboards/composition/widget-types"))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(
                withSuccess(
                    "[{\"type\":\"value\",\"capabilities\":{\"settings\":[]}}]",
                    MediaType.APPLICATION_JSON
                )
            );
        contexts().revalidate(List.of(ref, ref), "Bearer current");
        server.verify();
        server.reset();
        server
            .expect(requestTo("http://dashboards/dashboards/composition/widget-types"))
            .andRespond(
                withSuccess(
                    "[{\"type\":\"value\",\"capabilities\":{\"settings\":[{\"key\":\"changed\"}]}}]",
                    MediaType.APPLICATION_JSON
                )
            );
        assertThatThrownBy(() ->
            contexts().revalidate(List.of(ref), "Bearer current")
        ).isInstanceOfSatisfying(ResponseStatusException.class, error ->
            assertThat(error.getStatusCode().value()).isEqualTo(409)
        );
        server.verify();
    }

    @Test
    void metadataReferencesRevalidateTheirRealOwningDeviceAndSourceInsteadOfAnInventedResourceRoute() {
        var ref = new AuthorizedPlanningContext.ResourceRef(
            "data-ref",
            "dashboard-data",
            "choice-id",
            Map.of("deviceId", "owned", "sourceId", "source")
        );
        server
            .expect(requestTo("http://devices/devices/owned"))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("http://devices/devices/owned/data-sources/source"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        contexts().revalidate(
            List.of(
                ref,
                ref,
                new AuthorizedPlanningContext.ResourceRef("device", "device", "owned", Map.of()),
                new AuthorizedPlanningContext.ResourceRef(
                    "source",
                    "source",
                    "source",
                    Map.of("deviceId", "owned")
                )
            ),
            "Bearer current"
        );
        server.verify();
        server.reset();
        server
            .expect(requestTo("http://devices/devices/owned"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() ->
            contexts().revalidate(List.of(ref), "Bearer current")
        ).isInstanceOfSatisfying(ResponseStatusException.class, error ->
            assertThat(error.getStatusCode().value()).isEqualTo(404)
        );
        server.verify();
    }

    private final RestClient.Builder builder =
        com.voltcanary.common.web.JacksonRestClients.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PlanningInputGuard guard = new PlanningInputGuard();
    private final AuthenticatedUser actor = new AuthenticatedUser(
        UUID.randomUUID(),
        "test@example.invalid",
        "USER"
    );

    @Test
    void callerNamesAndAuthorityHintsNeverReplaceTheOwningCatalogueOrActualAccount() {
        UUID account = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        expectScope(account);
        server
            .expect(requestTo("http://devices/device-types"))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(
                withSuccess(
                    "[{\"code\":\"FRIDGE\",\"displayName\":\"Холодильник\"}]",
                    MediaType.APPLICATION_JSON
                )
            );
        server
            .expect(requestTo("http://devices/locations"))
            .andRespond(
                withSuccess(
                    "[{\"id\":\"" +
                        location +
                        "\",\"name\":\"Кухня\",\"privateMetadata\":\"never-forward\"}]",
                    MediaType.APPLICATION_JSON
                )
            );
        var prepared = contexts().prepare(
            actor,
            "Bearer current",
            "Создай устройство",
            Map.of(
                "accountId",
                UUID.randomUUID().toString(),
                "role",
                "ADMIN",
                "names",
                Map.of("location", "forged")
            )
        );
        assertThat(prepared.scope().accountId()).isEqualTo(account).isNotEqualTo(actor.userId());
        assertThat(prepared.context().toString())
            .contains("Кухня", "Холодильник")
            .doesNotContain("forged", "ADMIN", "never-forward", "Bearer");
        assertThat(prepared.refs()).hasSize(2);
        assertThat(prepared.refs().getLast().resourceId()).isEqualTo(location.toString());
        assertThat(prepared.refs().getLast().ref()).isNotEqualTo(location.toString());
        server.verify();
    }

    @Test
    void foreignResourceAndMissingAccountFailBeforeProviderContextCanBeBuilt() {
        UUID foreign = UUID.randomUUID();
        expectScope(UUID.randomUUID());
        server
            .expect(requestTo("http://devices/devices/" + foreign))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() ->
            contexts().prepare(
                actor,
                "Bearer current",
                "Открой устройство",
                Map.of("deviceId", foreign.toString())
            )
        ).isInstanceOfSatisfying(ResponseStatusException.class, error ->
            assertThat(error.getStatusCode().value()).isEqualTo(404)
        );
        server.verify();
        server.reset();
        server
            .expect(requestTo("http://devices/accounts/current/scope"))
            .andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() ->
            contexts().prepare(actor, "Bearer current", "Создай устройство", Map.of())
        ).isInstanceOfSatisfying(ResponseStatusException.class, error ->
            assertThat(error.getReason()).isEqualTo("ACCOUNT_SETUP_REQUIRED")
        );
        server.verify();
    }

    @Test
    void recursiveCredentialAndTotalSizeChecksRunBeforeAnyDomainRequest() {
        assertThatThrownBy(() ->
            contexts().prepare(
                actor,
                "Bearer current",
                "Создай устройство",
                Map.of("nested", List.of(Map.of("sourceToken", "private")))
            )
        ).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> guard.validate("Bearer private", Map.of())).isInstanceOf(
            ResponseStatusException.class
        );
        assertThatThrownBy(() ->
            guard.inspect(
                List.of(
                    "x".repeat(8000),
                    "x".repeat(8000),
                    "x".repeat(8000),
                    "x".repeat(8000),
                    "x".repeat(8000)
                )
            )
        ).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> guard.inspect(Map.of("value", Double.NaN))).isInstanceOf(
            ResponseStatusException.class
        );
        server.verify();
    }

    private void expectScope(UUID account) {
        server
            .expect(requestTo("http://devices/accounts/current/scope"))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(
                withSuccess(
                    "{\"id\":\"" +
                        account +
                        "\",\"userId\":\"" +
                        actor.userId() +
                        "\",\"status\":\"ACTIVE\"}",
                    MediaType.APPLICATION_JSON
                )
            );
    }

    @Test
    void duplicateReviewUsesOnlyAuthorizedExactNamesAndNotLooseSearchMatches() {
        server
            .expect(requestTo("http://devices/devices?search=Exact&limit=100"))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(
                withSuccess(
                    "[{\"name\":\"Exact extra\"},{\"name\":\"exact\"}]",
                    MediaType.APPLICATION_JSON
                )
            );
        assertThat(contexts().hasDeviceName("Exact", "Bearer current")).isTrue();
        server.verify();
    }

    @Test
    void persistedCreationResultsRenewResourceAccessBeforeTheyAreDisclosed() {
        UUID id = UUID.randomUUID();
        server
            .expect(requestTo("http://devices/devices/" + id))
            .andExpect(header("Authorization", "Bearer current"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        var execution = new com.voltcanary.aiagent.dto.ExecutePlanResponse(
            UUID.randomUUID(),
            "SUCCESS",
            List.of(
                new com.voltcanary.aiagent.dto.ActionExecutionResponse(
                    UUID.randomUUID(),
                    "CREATE_DEVICE",
                    "SUCCESS",
                    Map.of("deviceId", id.toString()),
                    null
                )
            )
        );
        assertThatThrownBy(() ->
            contexts().revalidateCreatedDevices(execution, "Bearer current")
        ).isInstanceOfSatisfying(ResponseStatusException.class, error ->
            assertThat(error.getStatusCode().value()).isEqualTo(404)
        );
        server.verify();
    }

    private AuthorizedPlanningContext contexts() {
        var properties = new AiAgentProperties();
        properties.getServices().setDeviceBaseUrl("http://devices");
        properties.getServices().setAlertBaseUrl("http://alerts");
        properties.getServices().setDashboardBaseUrl("http://dashboards");
        return new AuthorizedPlanningContext(properties, builder, guard, List.of());
    }
}
