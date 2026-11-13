package com.voltcanary.aiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.llm.*;
import com.voltcanary.aiagent.model.*;
import com.voltcanary.aiagent.security.AuthenticatedUser;
import com.voltcanary.aiagent.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceIntentFixtureTest {
    @Test void frozenRussianProviderFixturesPassThroughSanitizerAndTypedCompiler() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/evaluation/device-intent-ru-v1.json"));
        assertThat(fixture.path("cases").size()).isEqualTo(40);
        var actor = new AuthenticatedUser(UUID.randomUUID(), "fixture@example.invalid", "USER");
        var prepared = new AuthorizedPlanningContext.Prepared(new AuthorizedPlanningContext.Scope(actor.userId(), UUID.randomUUID()),
                Map.of(), List.of(new AuthorizedPlanningContext.ResourceRef("type-ref", "type", "FRIDGE", Map.of("displayName", "Холодильник"))));
        var sanitizer = new LlmPlanSanitizer();
        var compiler = new DeviceCreationCompiler();
        for (var row : fixture.path("cases")) {
            String message = row.path("message").asText();
            boolean clear = row.path("group").asText().equals("clear");
            String name = clear ? row.path("expected").path("name").asText()
                    : row.path("group").asText().equals("unsafe") ? "Север" : "Неуказанное";
            var fallback = new AgentPlan(UUID.randomUUID(), actor.userId(), message, "Fallback", "Fallback", List.of(), List.of(), Instant.now());
            var draft = new LlmPlanDraft("GEMINI", "fixture-small-model", "Stubbed provider intent", "Review",
                    List.of(new LlmActionDraft("CREATE_DEVICE", "Create", "Review", Map.of("name", name, "deviceTypeRef", "type-ref"))));
            var result = compiler.compile(sanitizer.sanitize(actor, message, draft, fallback), prepared);
            assertThat(result.actions().size()).as(row.path("id").asText()).isEqualTo(clear ? 1 : 0);
            if (clear) assertThat(result.actions().getFirst().parameters()).containsExactlyInAnyOrderEntriesOf(Map.of("name", name, "type", "FRIDGE"));
        }
    }
}
