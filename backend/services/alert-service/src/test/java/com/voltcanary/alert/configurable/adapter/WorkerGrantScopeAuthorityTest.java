package com.voltcanary.alert.configurable.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.alert.config.AlertAuthorizationProperties;
import com.voltcanary.alert.config.VclWorkerIdentityProperties;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceBindingDescription;
import com.voltcanary.vcl.VclSourceBindingProfile;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerGrantScopeAuthorityTest {
    @Test void renewsSourcePinsWithExactMachineReadWithoutUserBearer() throws Exception {
        var actor = UUID.randomUUID();
        var account = UUID.randomUUID();
        var rule = UUID.randomUUID();
        var grant = UUID.randomUUID();
        var binding = UUID.randomUUID();
        var field = UUID.randomUUID();
        var dataset = UUID.randomUUID();
        var datasetField = UUID.randomUUID();
        var device = UUID.randomUUID();
        var snapshot = UUID.randomUUID();
        var definition = new ObjectMapper().createObjectNode().put("id", binding.toString())
                .put("schemaRevision", 1).put("profile", VclSourceBindingProfile.VERSION)
                .put("configurationDigest", "a".repeat(64));
        definition.putArray("fields").addObject().put("id", field.toString())
                .put("type", "BOOLEAN").put("label", "Switch").put("nullable", true);
        String digest = VclCanonical.sha256(VclCanonical.encode(definition));
        var source = new VclSourceBindingDescription(binding, account, actor, device, 1, 1,
                snapshot, definition, digest, "b".repeat(64), "c".repeat(64), true, false,
                VclSourceBindingProfile.capabilities());
        var pin = new VclObservationReadiness.Pin(binding, account, device, "source", 1, snapshot, digest);
        var capture = new VclSourceCaptureDescription(source, VclObservationReadiness.absent(pin, "AWAITING_INPUT"));
        byte[] response = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsBytes(capture);
        var collection = new ObjectMapper().createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
        var spec = collection.putObject("spec").put("id", dataset.toString()).put("revision", 1).put("label", "Native");
        spec.putArray("fields").addObject().put("id", datasetField.toString())
                .put("label", "Name").put("type", "TEXT").put("maxLength", 120).put("nullable", true);
        spec.putArray("keyFieldIds");
        spec.putObject("storage").put("mode", "NATIVE").put("history", "NONE").putArray("indexedFieldIds");
        var parsed = new com.voltcanary.vcl.VclParser().parse(collection.toString());
        var nativeDefinition = (com.fasterxml.jackson.databind.node.ObjectNode) parsed.spec();
        var generation = UUID.randomUUID();
        nativeDefinition.put("generation", generation.toString()).put("dataVersion", 1);
        nativeDefinition.putObject("systemFields").put("recordIdFieldId", UUID.randomUUID().toString());
        var nativeResponse = new ObjectMapper().createObjectNode().put("id", dataset.toString())
                .put("schemaRevision", 1).put("schemaDigest", VclCanonical.documentDigest(parsed))
                .put("accessScopeDigest", "b".repeat(64)).put("generation", generation.toString())
                .put("dataVersion", 1).put("storageProfile", "native-dense-1")
                .put("liveQueryReady", true).put("historyReady", false);
        nativeResponse.set("definition", nativeDefinition);
        nativeResponse.putArray("readyIndexedFields");
        nativeResponse.putObject("queryCapabilities").put("profile", "native-live-sql-1");
        byte[] datasetResponse = nativeResponse.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(dataset, com.voltcanary.vcl.VclDatasetDescriptions.parse(datasetResponse).id());
        var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        var verifier = new VclWorkerAssertion(key, "worker-1", "device-service", Clock.systemUTC());
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            var path = exchange.getRequestURI().toString();
            var token = exchange.getRequestHeaders().getFirst("X-VCL-Worker-Assertion");
            boolean nativeRead = path.startsWith("/internal/vcl/datasets/");
            var signed = verifier.verify(token, nativeRead ? "DATASET_DESCRIPTION" : "SOURCE_CAPTURE",
                    nativeRead ? dataset : binding,
                    VclWorkerAssertion.requestDigest("GET", path, new byte[0]));
            assertEquals(actor, signed.actor());
            assertEquals(account, signed.account());
            assertEquals(rule, signed.rule());
            assertEquals(grant, signed.grant());
            byte[] answer = nativeRead ? datasetResponse : response;
            exchange.sendResponseHeaders(200, answer.length);
            try (var output = exchange.getResponseBody()) { output.write(answer); }
        });
        server.start();
        try {
            var properties = new AlertAuthorizationProperties();
            properties.setDeviceServiceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setTimeout(Duration.ofSeconds(2));
            var authority = new WorkerGrantScopeAuthority(JacksonRestClients.builder(), properties,
                    new VclWorkerIdentityProperties(Base64.getEncoder().encodeToString(key), "worker-1"));
            var worker = new VclWorkerAssertion.Scope(actor, account, rule, UUID.randomUUID(), grant, grant,
                    "GRANT_DESCRIPTION", "a".repeat(64));
            var scope = new AutomationGrantScope(List.of(new AutomationGrantScope.Resource(binding, 1, List.of(field))),
                    List.of(new AutomationGrantScope.Resource(dataset, 1, List.of(datasetField))),
                    Instant.now().plusSeconds(3600));
            var pins = authority.validate(worker, scope);
            assertEquals(2, calls.get());
            assertEquals(binding.toString(), pins.get(0).path("resourceId").asText());
            assertEquals(digest, pins.get(0).path("definitionDigest").asText());
            assertEquals("b".repeat(64), pins.get(0).path("accessScopeDigest").asText());
            assertEquals(dataset.toString(), pins.get(1).path("resourceId").asText());
            assertEquals(nativeResponse.path("schemaDigest").asText(), pins.get(1).path("schemaDigest").asText());
        } finally { server.stop(0); }
    }
}
