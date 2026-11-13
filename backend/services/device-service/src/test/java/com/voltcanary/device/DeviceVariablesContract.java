package com.voltcanary.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.common.events.telemetry.VariableExpression;
import com.voltcanary.device.dto.DataSourceRequest;
import com.voltcanary.device.dto.DeviceRequest;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.service.DataSourceService;
import com.voltcanary.device.service.DeviceService;
import com.voltcanary.device.variable.adapter.VariableLifecycleWorker;
import com.voltcanary.device.variable.application.VariableBindings;
import com.voltcanary.device.variable.application.VariableDefaults;
import com.voltcanary.device.variable.application.VariableDefinitions;
import com.voltcanary.device.variable.application.VariableRollouts;
import com.voltcanary.device.variable.domain.VariableValidation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "app.kafka.publish-enabled=false", "app.variables.activation-enabled=true"})
abstract class DeviceVariablesContract extends SignedUserTest {
    @Autowired VariableDefinitions definitions;
    @Autowired VariableBindings bindings;
    @Autowired VariableDefaults defaults;
    @Autowired VariableRollouts rollouts;
    @Autowired DeviceService devices;
    @Autowired DataSourceService sources;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @MockitoBean VariableLifecycleWorker worker;

    @Test
    void definitionsAreAccountScopedAndPublishedRevisionIsImmutable() {
        var owner = user();
        var created = definitions.create(owner, "FRIDGE", draft("chamber", null));
        UUID id = created.definition().id();
        var published = definitions.publish(owner, "FRIDGE", id, new VariableDefinitions.Publication(1, 1, true));
        assertThat(published.definition().version()).isEqualTo(2);
        assertThat(published.revisions().getFirst().state()).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> definitions.publish(owner, "FRIDGE", id,
                new VariableDefinitions.Publication(1, 1, false))).isInstanceOf(ResponseStatusException.class);
        var revisionTwo = definitions.revise(owner, "FRIDGE", id, draft("chamber", 2));
        assertThat(revisionTwo.revisions()).hasSize(2);
        assertThat(revisionTwo.revisions().getFirst()).isEqualTo(published.revisions().getFirst());
        assertThatThrownBy(() -> definitions.detail(user(), "FRIDGE", id))
                .isInstanceOf(com.voltcanary.device.exception.NotFoundException.class);
        assertThat(definitions.list(user(), "FRIDGE")).isEmpty();
        assertThatThrownBy(() -> definitions.create(owner, "FRIDGE", draft("temperatureC", null)))
                .isInstanceOf(IllegalArgumentException.class);
        var changed = draft("otherKey", 3);
        assertThatThrownBy(() -> definitions.revise(owner, "FRIDGE", id, changed))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sourceBindingRequiresPublishedRevisionRetainedInputsAndOwner() {
        var owner = user();
        UUID device = device(owner);
        UUID source = source(owner, device, true);
        var variable = definitions.create(owner, "FRIDGE", draft("chamber", null));
        UUID id = variable.definition().id();
        var edit = new VariableBindings.Edit(id, 1, 0, Map.of("x", "sensorTemperatureRaw"), true);
        assertThatThrownBy(() -> bindings.save(owner, device, source, edit))
                .isInstanceOf(ResponseStatusException.class);
        definitions.publish(owner, "FRIDGE", id, new VariableDefinitions.Publication(1, 1, false));
        var saved = bindings.save(owner, device, source, edit).getFirst();
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThatThrownBy(() -> devices.update(owner, device,
                new DeviceRequest(null, "Changed", "WASHING_MACHINE", null, null, Map.of())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Disable device variable bindings");
        assertThatThrownBy(() -> bindings.save(owner, device, source, edit))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> bindings.list(user(), device, source))
                .isInstanceOf(com.voltcanary.device.exception.NotFoundException.class);
        var sourceRecord = sources.get(owner, device, source);
        assertThatThrownBy(() -> sources.update(owner, device, source, sourceRequest(sourceRecord.externalId(), false)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("required");
        assertThat(sources.get(owner, device, source).mapping().toString()).contains("store=true");
        assertThat(jdbc.queryForObject("select count(*) from source_processing_bundles where data_source_id=?",
                Integer.class, source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload_json from device_event_outbox where message_key=? and topic=?",
                String.class, source.toString(), "source.processing-config.v1"))
                .contains("sensorTemperatureRaw", "generation", "digest").doesNotContain("tokenHash", "credentialReferences");
    }

    @Test
    void futureDeviceInheritsExplicitDefaultButAmbiguousDeviceRemainsUnbound() {
        var owner = user();
        UUID id = definitions.create(owner, "FRIDGE", draft("chamber", null)).definition().id();
        definitions.publish(owner, "FRIDGE", id, new VariableDefinitions.Publication(1, 1, true));
        defaults.configure(owner, "FRIDGE", id,
                new VariableDefaults.Edit(2, 1, "SENSOR", Map.of("x", "sensorTemperatureRaw")));
        UUID first = device(owner);
        UUID source = source(owner, first, true);
        UUID ambiguous = device(owner);
        source(owner, ambiguous, true);
        source(owner, ambiguous, true);
        var result = defaults.reconcile(owner, "FRIDGE", id);
        assertThat(result).anyMatch(item -> item.deviceId().equals(first) && item.status().equals("PROPAGATING"));
        assertThat(result).anyMatch(item -> item.deviceId().equals(ambiguous) && item.status().equals("UNBOUND"));
        assertThat(bindings.list(owner, first, source).getFirst().inherited()).isTrue();
        var revised = definitions.revise(owner, "FRIDGE", id, draft("chamber", 3));
        definitions.publish(owner, "FRIDGE", id,
                new VariableDefinitions.Publication(revised.definition().version(), 2, true));
        defaults.reconcile(owner, "FRIDGE", id);
        assertThat(bindings.list(owner, first, source).getFirst().revision()).isEqualTo(1);
    }

    @Test
    void rolloutFailuresAreResumableAndSuccessfulTargetsDoNotApplyTwice() {
        var owner = user();
        UUID device = device(owner);
        UUID good = source(owner, device, true);
        UUID bad = source(owner, device, false);
        UUID variable = definitions.create(owner, "FRIDGE", draft("chamber", null)).definition().id();
        definitions.publish(owner, "FRIDGE", variable, new VariableDefinitions.Publication(1, 1, false));
        var targets = rollouts.create(owner, "FRIDGE", variable,
                new VariableRollouts.Request(1, List.of(new VariableRollouts.Target(device, good),
                        new VariableRollouts.Target(device, bad)), Map.of("x", "sensorTemperatureRaw"), true));
        assertThat(rollouts.resume(owner, "FRIDGE", variable, targets.getFirst().id()).status()).isEqualTo("APPLIED");
        assertThat(rollouts.resume(owner, "FRIDGE", variable, targets.getFirst().id()).status()).isEqualTo("APPLIED");
        assertThat(bindings.list(owner, device, good).getFirst().version()).isEqualTo(1);
        var failed = rollouts.resume(owner, "FRIDGE", variable, targets.getLast().id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(bindings.list(owner, device, bad)).isEmpty();
        sources.update(owner, device, bad, sourceRequest(sources.get(owner, device, bad).externalId(), true));
        assertThat(rollouts.resume(owner, "FRIDGE", variable, failed.id()).status()).isEqualTo("APPLIED");
    }

    @Test
    void queuedRolloutCannotOverwriteAnInterveningManualBindingEdit() {
        var owner = user();
        UUID device = device(owner), source = source(owner, device, true);
        UUID variable = definitions.create(owner, "FRIDGE", draft("chamber", null)).definition().id();
        definitions.publish(owner, "FRIDGE", variable, new VariableDefinitions.Publication(1, 1, false));
        var targets = rollouts.create(owner, "FRIDGE", variable, new VariableRollouts.Request(1,
                List.of(new VariableRollouts.Target(device, source)), Map.of("x", "sensorTemperatureRaw"), true));
        bindings.save(owner, device, source, new VariableBindings.Edit(variable, 1, 0, Map.of("x", "sensorTemperatureRaw"), true));
        assertThat(rollouts.resume(owner, "FRIDGE", variable, targets.getFirst().id()).status()).isEqualTo("FAILED");
        assertThat(bindings.list(owner, device, source).getFirst().version()).isEqualTo(1);
    }

    @Test
    void archiveRetainsRevisionAndQueuesDisableIntent() {
        var owner = user();
        UUID device = device(owner);
        UUID source = source(owner, device, true);
        UUID variable = definitions.create(owner, "FRIDGE", draft("chamber", null)).definition().id();
        definitions.publish(owner, "FRIDGE", variable, new VariableDefinitions.Publication(1, 1, false));
        bindings.save(owner, device, source,
                new VariableBindings.Edit(variable, 1, 0, Map.of("x", "sensorTemperatureRaw"), true));
        definitions.archive(owner, "FRIDGE", variable, 2);
        assertThat(definitions.detail(owner, "FRIDGE", variable).revisions()).hasSize(1);
        UUID target = jdbc.queryForObject("select id from variable_rollout_targets where variable_id=?", UUID.class, variable);
        assertThat(rollouts.resume(owner, "FRIDGE", variable, target).status()).isEqualTo("APPLIED");
        assertThat(bindings.list(owner, device, source).getFirst().enabled()).isFalse();
        assertThat(jdbc.queryForObject("select max(generation) from source_processing_bundles where data_source_id=?",
                Long.class, source)).isEqualTo(2);
        assertThatThrownBy(() -> bindings.save(owner, device, source,
                new VariableBindings.Edit(variable, 1, 2, Map.of("x", "sensorTemperatureRaw"), true)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void routesAuthenticateAndRejectStaleMutations() {
        var owner = user();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(com.voltcanary.common.test.SignedUserTokens.create(owner.id(), owner.email(), owner.role()));
        headers.set("X-User-Email", owner.email());
        headers.set("X-User-Role", "USER");
        var created = http.exchange("/device-types/FRIDGE/variables", HttpMethod.POST,
                new HttpEntity<>(draft("routeVariable", null), headers), VariableDefinitions.Detail.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID variable = created.getBody().definition().id();
        var stale = http.exchange("/device-types/FRIDGE/variables/" + variable + "/publish", HttpMethod.POST,
                new HttpEntity<>(new VariableDefinitions.Publication(9, 1, false), headers), String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var denied = http.getForEntity("/device-types/FRIDGE/variables", String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsUnsafeAndDimensionallyInvalidExpressions() {
        var candidate = draft("valid", null);
        assertThatThrownBy(() -> VariableValidation.contract(new VariableContract(UUID.randomUUID(), 1, "valid",
                "Valid", "DERIVED", "W", "v1", candidate.inputs(), candidate.expression(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        var unsafe = new VariableExpression("CALL", null, "http", null, null);
        assertThatThrownBy(() -> VariableValidation.contract(new VariableContract(UUID.randomUUID(), 1, "valid",
                "Valid", "DERIVED", "C", "v1", candidate.inputs(), unsafe, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID device(AuthenticatedUser user) {
        return devices.create(user, new DeviceRequest(null, "Variable fridge", "FRIDGE", null, null, Map.of())).id();
    }

    private UUID source(AuthenticatedUser user, UUID device, boolean store) {
        return sources.create(user, device, sourceRequest("variable-" + UUID.randomUUID(), store)).id();
    }

    private DataSourceRequest sourceRequest(String externalId, boolean store) {
        Map<String, Object> field = Map.of("path", "sensorTemperatureRaw", "key", "sensorTemperatureRaw",
                "label", "Raw temperature", "type", "number", "unit", "C", "store", store, "display", true);
        return new DataSourceRequest(com.voltcanary.device.domain.DataSourceType.SENSOR,
                com.voltcanary.device.domain.DataSourceProtocol.REST, externalId,
                Map.of("telemetryPolicy", Map.of("fields", List.of(field))), Map.of(), Map.of(), null);
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(UUID.randomUUID(), "variable@example.com", "USER");
    }

    private VariableDefinitions.Draft draft(String key, Integer version) {
        var expression = new VariableExpression("SUBTRACT", null, null,
                new VariableExpression("MULTIPLY", null, null, VariableExpression.input("x"),
                        VariableExpression.constant(0.1)), VariableExpression.constant(0.8));
        return new VariableDefinitions.Draft(key, "Temperature", "DERIVED", "C",
                Map.of("x", new VariableContract.Input("C", null, null)), expression, -50.0, 150.0, version);
    }
}
