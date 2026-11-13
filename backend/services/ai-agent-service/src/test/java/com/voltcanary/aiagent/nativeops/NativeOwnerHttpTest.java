package com.voltcanary.aiagent.nativeops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.aiagent.config.AiAgentProperties;
import com.voltcanary.aiagent.model.AgentAction;
import com.voltcanary.aiagent.service.ActionExecutionBoundary;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.voltcanary.aiagent.nativeops.NativeAuthoringFixtures.AUTH;
import static com.voltcanary.aiagent.nativeops.NativeAuthoringFixtures.JSON;
import static com.voltcanary.aiagent.nativeops.NativeAuthoringFixtures.KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Controlled ordinary HTTP contracts, not evidence of signed owner SQL or model qualification. */
class NativeOwnerHttpTest {
    private record Request(String method, String path, JsonNode body, String authorization) { }
    private HttpServer server;
    private final List<Request> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String status;
    private volatile String mode;
    private NativeOwnerClient owner;
    private NativeTableExecutor executor;
    private final ActionExecutionBoundary boundary = new ActionExecutionBoundary();

    @BeforeEach void start() throws Exception {
        status = "PREPARED";
        mode = "NORMAL";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] input = exchange.getRequestBody().readAllBytes();
            requests.add(new Request(exchange.getRequestMethod(), path, input.length == 0 ? JSON.nullNode() : JSON.readTree(input), exchange.getRequestHeaders().getFirst("Authorization")));
            JsonNode response;
            int code = 200;
            if (path.equals("/data/language")) {
                var language = NativeAuthoringFixtures.language();
                if (mode.equals("REGISTRY_CHANGED")) ((ObjectNode) language.path("registry")).put("registryDigest", "f".repeat(64));
                response = language;
            } else if (path.endsWith("/apply")) {
                status = "SUCCEEDED";
                if (mode.equals("LOST_APPLY_ACK")) { exchange.close(); return; }
                if (mode.equals("PENDING")) { status = "UNKNOWN"; code = 202; }
                response = NativeAuthoringFixtures.view(status);
            } else if (path.endsWith("/reconcile")) {
                status = "SUCCEEDED";
                response = NativeAuthoringFixtures.view(status);
            } else if (path.startsWith("/data/reviews/")) {
                if (mode.equals("ACCESS_LOST")) { code = 403; response = JSON.createObjectNode().put("private", "upstream-secret"); }
                else {
                    var view = NativeAuthoringFixtures.view(status);
                    if (mode.equals("RETARGET")) view.put("resourceId", UUID.randomUUID().toString());
                    if (mode.equals("REVIEW_CHANGED")) view.put("reviewDigest", "f".repeat(64));
                    response = view;
                }
            } else if (path.startsWith("/data/datasets/")) {
                var dataset = NativeAuthoringFixtures.dataset();
                if (mode.equals("DATASET_CHANGED")) ((ObjectNode) dataset.path("definition")).put("label", "Substituted table");
                response = dataset;
            } else { code = 500; response = JSON.createObjectNode().put("error", "Unexpected native call"); }
            byte[] bytes = JSON.writeValueAsBytes(response);
            if (mode.equals("DUPLICATE_JSON") && path.startsWith("/data/reviews/")) bytes = "{\"reviewId\":\"a\",\"reviewId\":\"b\"}".getBytes(StandardCharsets.UTF_8);
            if (mode.equals("OVERSIZED") && path.startsWith("/data/reviews/")) bytes = ("{\"private\":\"" + "x".repeat(1_048_576) + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            catch (java.io.IOException closedByBoundedClient) { /* Oversized/invalid fixture responses may be rejected early. */ }
        });
        server.start();
        var properties = new AiAgentProperties();
        properties.getServices().setDeviceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        owner = new NativeOwnerClient(com.voltcanary.common.web.JacksonRestClients.builder(), properties);
        executor = new NativeTableExecutor(owner, JSON);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void exactOwnerReviewIsAppliedOnceAndReadBackThroughTheOrdinaryDatasetApi() {
        var action = NativeAuthoringFixtures.action();
        var result = boundary.run(action, new LinkedHashMap<>(), () -> executor.execute(action, AUTH));
        assertEquals("SUCCESS", result.status());
        assertEquals(NativeAuthoringFixtures.id("resourceId").toString(), result.result().get("datasetId"));
        assertEquals("/data?datasetId=" + NativeAuthoringFixtures.id("resourceId"), result.result().get("route"));
        var applied = requests.stream().filter(request -> request.path().endsWith("/apply")).toList();
        assertEquals(1, applied.size());
        assertEquals(JSON.valueToTree(Map.of("reviewRevision", 1, "reviewDigest", NativeAuthoringFixtures.DIGEST, "idempotencyKey", KEY)), applied.getFirst().body());
        assertTrue(requests.stream().allMatch(request -> AUTH.equals(request.authorization())));
        assertFalse(requests.stream().anyMatch(request -> request.path().contains("reservations") || request.path().equals("/data/dataset-reviews")));
        assertEquals("SUCCESS", boundary.run(action, new LinkedHashMap<>(), () -> executor.execute(action, AUTH)).status());
        assertEquals(1, requests.stream().filter(request -> request.path().endsWith("/apply")).count());
    }

    @Test void lostApplyAcknowledgementReconcilesTheOriginalReceiptWithoutNewPreparationOrDispatch() {
        mode = "LOST_APPLY_ACK";
        var action = NativeAuthoringFixtures.action();
        assertEquals("UNKNOWN", boundary.run(action, new LinkedHashMap<>(), () -> executor.execute(action, AUTH)).status());
        assertEquals("SUCCEEDED", status);
        int calls = requests.size();
        mode = "NORMAL";
        assertEquals("SUCCESS", boundary.run(action, new LinkedHashMap<>(), () -> executor.reconcile(action, AUTH)).status());
        assertTrue(requests.subList(calls, requests.size()).stream().allMatch(request -> request.method().equals("GET")));
        assertEquals(1, requests.stream().filter(request -> request.path().endsWith("/apply")).count());
    }

    @Test void pendingOwnerResponsePreservesUnknownAndUsesOnlyTheKnownReviewForReconciliation() {
        mode = "PENDING";
        var action = NativeAuthoringFixtures.action();
        assertEquals("UNKNOWN", boundary.run(action, new LinkedHashMap<>(), () -> executor.execute(action, AUTH)).status());
        mode = "NORMAL";
        assertEquals("SUCCESS", boundary.run(action, new LinkedHashMap<>(), () -> executor.reconcile(action, AUTH)).status());
        var recovered = requests.stream().filter(request -> request.path().endsWith("/reconcile")).toList();
        assertEquals(1, recovered.size());
        assertEquals("/data/reviews/" + NativeAuthoringFixtures.id("reviewId") + "/reconcile", recovered.getFirst().path());
        assertEquals(JSON.createObjectNode(), recovered.getFirst().body());
    }

    @ParameterizedTest @ValueSource(strings = {"RETARGET", "REVIEW_CHANGED"})
    void changedReviewedIdentityCannotDispatch(String failure) {
        mode = failure;
        var action = NativeAuthoringFixtures.action();
        assertEquals("FAILED", boundary.run(action, new LinkedHashMap<>(), () -> executor.execute(action, AUTH)).status());
        assertTrue(requests.stream().allMatch(request -> request.method().equals("GET")));
        assertEquals("UNKNOWN", boundary.run(action, new LinkedHashMap<>(), () -> executor.reconcile(action, AUTH)).status());
    }

    @ParameterizedTest @ValueSource(strings = {"DUPLICATE_JSON", "OVERSIZED", "ACCESS_LOST"})
    void unreadableOwnerReceiptNeverProvesNoEffectOrLeaksUpstreamBody(String failure) {
        mode = failure;
        var action = NativeAuthoringFixtures.action();
        var result = boundary.run(action, new LinkedHashMap<>(), () -> executor.reconcile(action, AUTH));
        assertEquals("UNKNOWN", result.status());
        assertTrue(requests.stream().allMatch(request -> request.method().equals("GET")));
        assertFalse(result.toString().contains("upstream-secret"));
    }

    @Test void succeededReceiptWithoutTheExactActualSchemaRemainsUnknown() {
        mode = "DATASET_CHANGED";
        status = "SUCCEEDED";
        var action = NativeAuthoringFixtures.action();
        assertEquals("UNKNOWN", boundary.run(action, new LinkedHashMap<>(), () -> executor.execute(action, AUTH)).status());
        assertTrue(requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test void expiredReviewInspectionIsGetOnlyForBothKnownAndUnresolvedOutcomes() {
        var action = NativeAuthoringFixtures.action();
        status = "UNKNOWN";
        assertEquals("UNKNOWN", boundary.run(action, new LinkedHashMap<>(), () -> executor.inspectOutcome(action, AUTH)).status());
        status = "SUCCEEDED";
        assertEquals("SUCCESS", boundary.run(action, new LinkedHashMap<>(), () -> executor.inspectOutcome(action, AUTH)).status());
        assertTrue(requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test void missingOrForgedServerReviewMetadataIsRejectedBeforeHttp() {
        var action = NativeAuthoringFixtures.action();
        var invalid = new AgentAction(action.id(), action.tool(), action.title(), action.description(), true, Map.of("document", action.parameters().get("document")));
        assertEquals("FAILED", boundary.run(invalid, new LinkedHashMap<>(), () -> executor.execute(invalid, AUTH)).status());
        assertEquals("UNKNOWN", boundary.run(invalid, new LinkedHashMap<>(), () -> executor.reconcile(invalid, AUTH)).status());
        assertTrue(requests.isEmpty());
    }

    @Test void registryVersionAndAuthenticationAreCheckedWithoutServiceFallback() {
        assertEquals(NativeAuthoringFixtures.language(), owner.language(AUTH));
        mode = "REGISTRY_CHANGED";
        assertThrows(IllegalArgumentException.class, () -> owner.language(AUTH));
        int count = requests.size();
        assertThrows(IllegalArgumentException.class, () -> owner.language("Basic unsupported"));
        assertEquals(count, requests.size());
    }

    @Test void redirectsCannotForwardTheSignedAuthorizationToAnotherEndpoint() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", exchange -> { hits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        destination.start();
        var redirect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirect.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + destination.getAddress().getPort() + "/private");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        redirect.start();
        try {
            var properties = new AiAgentProperties();
            properties.getServices().setDeviceBaseUrl("http://127.0.0.1:" + redirect.getAddress().getPort());
            var client = new NativeOwnerClient(com.voltcanary.common.web.JacksonRestClients.builder(), properties);
            assertThrows(NativeOwnerClient.Uncertain.class, () -> client.language(AUTH));
            assertEquals(0, hits.get());
        } finally { redirect.stop(0); destination.stop(0); }
    }
}
