package com.voltcanary.aiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.trace.AssistantTrace;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

final class PlanningContextRead {
    private PlanningContextRead() { }

    static JsonNode read(RestClient client, String path, String authorization, boolean accountSetup) {
        try (var trace = AssistantTrace.start("CONTEXT_READ", Map.of("path", path))) {
            try {
                var response = client.get().uri(path).header("Authorization", authorization).retrieve().body(JsonNode.class);
                trace.success(Map.of("responseKind", response == null ? "EMPTY" : response.getNodeType().name()));
                return response;
            } catch (RestClientResponseException failure) {
                trace.failure("CONTEXT_HTTP_" + failure.getStatusCode().value(), Map.of());
                if (accountSetup && failure.getStatusCode().value() == 409) throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_SETUP_REQUIRED");
                if (failure.getStatusCode().is4xxClientError()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource selection unavailable");
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authorized context unavailable");
            } catch (RestClientException failure) {
                trace.failure("CONTEXT_UNAVAILABLE", Map.of());
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authorized context unavailable");
            }
        }
    }
}
