package com.voltcanary.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JacksonRestClientsTest {
    record Reading(Instant timestamp) { }
    record Owner(String accountId) { }

    @Test
    void preservesIsoDatesJsonNodesAndForwardCompatiblePartialDtos() {
        var builder = JacksonRestClients.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://domain/readings"))
                .andExpect(content().json("{\"timestamp\":\"2026-10-29T00:00:00Z\"}"))
                .andRespond(withSuccess("{\"value\":42}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://domain/owner"))
                .andRespond(withSuccess("{\"accountId\":\"owned\",\"deviceId\":\"extra-field\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://domain/raw"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        var client = builder.build();
        var value = client.post().uri("http://domain/readings").body(new Reading(Instant.parse("2026-10-29T00:00:00Z")))
                .retrieve().body(JsonNode.class);
        assertEquals(42, value.path("value").asInt());
        assertEquals("owned", client.get().uri("http://domain/owner").retrieve().body(Owner.class).accountId());
        assertEquals("{\"ok\":true}", client.get().uri("http://domain/raw").retrieve().body(String.class));
        server.verify();
    }
}
