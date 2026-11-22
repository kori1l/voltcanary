package com.voltcanary.aiagent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.aiagent.adapter.springai.SpringAiCapabilityEmbeddings;
import com.voltcanary.aiagent.config.WidgetEmbeddingProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpringAiCapabilityEmbeddingsTest {

    @Test
    void sdkUsesDedicatedEndpointModelAndCredentialWithoutRetries() throws Exception {
        var calls = new AtomicInteger();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            int attempt = calls.incrementAndGet();
            assertEquals(
                "Bearer fixture-key",
                exchange.getRequestHeaders().getFirst("Authorization")
            );
            var request = mapper.readTree(exchange.getRequestBody());
            assertEquals("embedding-fixture", request.path("model").asText());
            String body =
                attempt == 1
                    ? "{\"object\":\"list\",\"model\":\"embedding-fixture\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0]},{\"object\":\"embedding\",\"index\":1,\"embedding\":[0,1]}],\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}"
                    : "{\"error\":{\"message\":\"fixture unavailable\",\"type\":\"server_error\"}}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(attempt == 1 ? 200 : 500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties = new WidgetEmbeddingProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "embedding-fixture",
                "fixture-key",
                1000
            );
            var observations = ObservationRegistry.create();
            var observed = new AtomicInteger();
            observations.observationConfig().observationHandler(
                new ObservationHandler<Observation.Context>() {
                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }

                    @Override
                    public void onStop(Observation.Context context) {
                        observed.incrementAndGet();
                    }
                }
            );
            var adapter = new SpringAiCapabilityEmbeddings(properties, observations);
            var result = adapter.embed(List.of("catalogue alpha", "catalogue beta"));
            assertArrayEquals(new float[] { 1, 0 }, result.get(0));
            assertArrayEquals(new float[] { 0, 1 }, result.get(1));
            assertThrows(RuntimeException.class, () -> adapter.embed(List.of("query")));
            assertEquals(2, calls.get());
            assertEquals(2, observed.get());
            assertFalse(properties.toString().contains("fixture-key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidDeploymentSettingsFailBeforeNetworkAccess() {
        assertThrows(IllegalArgumentException.class, () ->
            new SpringAiCapabilityEmbeddings(
                new WidgetEmbeddingProperties(
                    true,
                    "https://user:secret@example.invalid/v1",
                    "model",
                    "",
                    1000
                )
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SpringAiCapabilityEmbeddings(
                new WidgetEmbeddingProperties(true, "http://localhost/v1", "", "", 1000)
            )
        );
    }
}
