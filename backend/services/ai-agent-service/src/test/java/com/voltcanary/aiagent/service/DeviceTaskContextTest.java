package com.voltcanary.aiagent.service;

import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.model.AgentPlan;
import com.voltcanary.aiagent.model.AgentTool;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceTaskContextTest {
    @Test void acceptsOriginalNameLabelAndPlainReplacementAnswers() {
        String original = "Создай устройство для реального автобуса HSL\nНазвание: HSL 67 — 0018-01092\nТип устройства: VEHICLE\nПодключи MQTT /hfp/v2/journey/ongoing/vp/bus/0018/01092/#";
        var first = DeviceTaskContext.prepare(original, Map.of());
        assertThat(first).containsEntry("name", "HSL 67 — 0018-01092");
        for (String answer : List.of("Мой автобус 1", "\"Автобус 1\"", "Bus 1")) {
            var values = DeviceTaskContext.prepare(original, Map.of("originalDeviceRequest", original,
                    "name", "old", "priorAssistantQuestion", "Как назвать устройство?",
                    "clarificationAnswers", List.of("old", answer)));
            assertThat(values.get("name")).isEqualTo(answer.replace("\"", ""));
        }
    }

    @Test void compilesTheKnownNameAndConnectionFromOriginalRequestOnly() {
        String original = "Создай один автобус HSL. Подключи MQTT /hfp/v2/journey/ongoing/vp/bus/0012/02512/#";
        var user = UUID.randomUUID();
        var action = new AgentAction(UUID.randomUUID(), AgentTool.CREATE_DEVICE, "create", "create", true,
                Map.of("name", "old", "deviceTypeRef", "vehicle"));
        var plan = new AgentPlan(UUID.randomUUID(), user, original + "\nАвтобус 1\nBus 1", "create", "create", List.of(), List.of(action), Instant.now());
        var context = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(user, UUID.randomUUID()),
                Map.of("name", "Bus 1", "originalDeviceRequest", original, "deviceSetupCapabilities", List.of("HSL_HFP")),
                List.of(new AuthorizedPlanningContext.ResourceRef("vehicle", "type", "VEHICLE", Map.of("displayName", "Vehicle"))));
        var compiled = new DeviceCreationCompiler().compile(plan, context);
        assertThat(compiled.actions()).hasSize(1);
        assertThat(compiled.actions().getFirst().parameters()).containsEntry("name", "Bus 1")
                .containsEntry("connection", Map.of("provider", "HSL_HFP", "mode", "bus", "vehicleKey", "0012-02512"));
        assertThat(compiled.explanation()).contains("После подтверждения");
    }

    @Test void ambiguousAndUnsupportedIdentitiesNeverBecomeNetworkTargets() {
        assertThatThrownBy(() -> DeviceConnectionIntent.hsl("Подключи автобус HSL 0012-02512 и 0012-02417")).isInstanceOf(IllegalArgumentException.class);
        assertThat(DeviceConnectionIntent.hsl("Создай холодильник и подключи REST" )).isEmpty();
        assertThat(DeviceTaskContext.prepare("Создай дашборд для выбранного устройства", Map.of())).doesNotContainKey("originalDeviceRequest");
        var routing = new DashboardPlanningContext(new com.voltcanary.aiagent.config.AiAgentProperties(), com.voltcanary.common.web.JacksonRestClients.builder(), new PlanningInputGuard());
        assertThat(routing.requested("Создай устройство для автобуса, потом настроим дашборд")).isFalse();
        assertThat(routing.requested("Создай дашборд для выбранного устройства")).isTrue();
    }
}
