package com.voltcanary.aiagent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.aiagent.orchestration.contract.SkillRegistry;
import com.voltcanary.aiagent.orchestration.routing.ModelRouting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedExchangeFactoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PreparedExchangeFactory factory = new PreparedExchangeFactory(mapper);

    @Test
    void preservesTheExactInputAndDigest() throws Exception {
        String input = "{ \"message\": \"Трамвай\" }";
        String body = mapper.createObjectNode().put("instructions", "instructions").put("input", input).toString();
        var exchange = factory.prepare(body, "JSON_TEXT");
        assertEquals(input, exchange.input());
        assertEquals(SkillRegistry.digest(body), exchange.digest());
    }

    @Test
    void rejectsChangedToolSelectionAndAnUnexpectedSchema() {
        var body = mapper.createObjectNode().put("instructions", "instructions").put("input", "{}");
        body.putArray("tools").addObject().put("name", "assistant_proposal").putObject("parameters");
        body.putObject("tool_choice").put("name", "different_tool");
        body.put("parallel_tool_calls", false);
        assertThrows(ModelRouting.Stop.class, () -> factory.prepare(body.toString(), "NATIVE_TOOLS"));
        assertThrows(ModelRouting.Stop.class, () -> factory.prepare(body.toString(), "JSON_TEXT"));
    }
}
