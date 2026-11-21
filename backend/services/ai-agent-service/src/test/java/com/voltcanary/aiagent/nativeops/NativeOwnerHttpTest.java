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
    private volatile JsonNode sourceCapture;
    private volatile JsonNode mappingPreview;
    private volatile JsonNode mappingReceipt;
    private volatile JsonNode mappingReadback;
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
                if (mode.equals("REGISTRY_BODY_CHANGED")) ((ObjectNode) language.path("registry")).putArray("operators");
                response = language;
            } else if (path.endsWith("/capture")) {
                response = sourceCapture;
            } else if (path.endsWith("/mapping-preview")) {
                response = mappingPreview;
            } else if (path.endsWith("/mapping-confirmation") || path.contains("/mapping-receipts/")) {
                response = mappingReceipt;
            } else if (path.startsWith("/data/source-bindings/")) {
                response = exchange.getRequestURI().getQuery().equals("schemaRevision=3")
                        ? mappingReadback : sourceCapture.path("source");
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
                    if (mode.equals("CONTRADICTORY_RECEIPT")) view.put("outcomeCode", "OUTCOME_UNKNOWN");
                    if (mode.equals("FIELD_RESERVATION_CHANGED")) {
                        var fields = (ObjectNode) view.path("fieldIdsByKey");
                        fields.put(fields.fieldNames().next(), UUID.randomUUID().toString());
                    }
                    response = view;
                }
            } else if (path.startsWith("/data/datasets/")) {
                var dataset = NativeAuthoringFixtures.dataset();
                if (mode.equals("DATASET_CHANGED")) ((ObjectNode) dataset.path("definition")).put("label", "Substituted table");
                if (mode.equals("INDEX_METADATA_CHANGED")) dataset.putArray("readyIndexedFields").add(UUID.randomUUID().toString());
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

    @Test void sourceCaptureIsBoundToRequestedActorAccountAndRevision() {
        var binding = UUID.randomUUID();
        var account = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var device = UUID.randomUUID();
        var snapshot = UUID.randomUUID();
        var definition = JSON.createObjectNode().put("id", binding.toString()).put("schemaRevision", 2)
                .put("profile", com.voltcanary.vcl.VclSourceBindingProfile.VERSION).put("configurationDigest", "a".repeat(64));
        definition.putArray("fields").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "BOOLEAN").put("label", "Switch").put("nullable", true);
        String digest = com.voltcanary.vcl.VclCanonical.sha256(com.voltcanary.vcl.VclCanonical.encode(definition));
        var source = new com.voltcanary.vcl.VclSourceBindingDescription(binding, account, actor, device, 2, 2,
                snapshot, definition, digest, "a".repeat(64), "a".repeat(64), true, false,
                com.voltcanary.vcl.VclSourceBindingProfile.capabilities());
        var pin = new com.voltcanary.vcl.VclObservationReadiness.Pin(binding, account, device, "source", 2, snapshot, digest);
        var readiness = com.voltcanary.vcl.VclObservationReadiness.absent(pin, "AWAITING_INPUT");
        sourceCapture = JSON.valueToTree(new com.voltcanary.vcl.VclSourceCaptureDescription(source, readiness));
        var scope = new com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope(actor, account);

        assertEquals("AWAITING_INPUT", owner.sourceCapture(AUTH, scope, binding, 2).capture().state());
        assertThrows(IllegalArgumentException.class, () -> owner.sourceCapture(AUTH, scope, binding, 1));
        assertThrows(IllegalArgumentException.class, () -> owner.sourceCapture(AUTH,
                new com.voltcanary.aiagent.service.AuthorizedPlanningContext.Scope(UUID.randomUUID(), account), binding, 2));
        assertTrue(requests.stream().allMatch(request -> request.method().equals("GET") && AUTH.equals(request.authorization())));

        var fieldId = UUID.fromString(definition.at("/fields/0/id").asText());
        var conversion = JSON.createObjectNode().put("method", "identity");
        var sample = JSON.getNodeFactory().booleanNode(true);
        var preview = JSON.createObjectNode().put("bindingId", binding.toString()).put("schemaRevision", 2)
                .put("schemaSnapshotId", snapshot.toString())
                .put("mappingDigest", com.voltcanary.vcl.VclSourceMappingReview.digest(digest, source.accessScopeDigest(), fieldId, conversion));
        preview.putObject("literal").put("type", "BOOLEAN").set("value", sample);
        mappingPreview = preview;
        assertEquals(preview, owner.previewMapping(AUTH, scope, binding, 2, fieldId, conversion, sample));
        assertEquals(fieldId.toString(), requests.getLast().body().path("fieldId").asText());
        mappingPreview = preview.deepCopy().put("mappingDigest", "f".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> owner.previewMapping(AUTH, scope, binding, 2, fieldId, conversion, sample));
        var alteredLiteral = preview.deepCopy();
        ((ObjectNode) alteredLiteral.path("literal")).put("value", false);
        mappingPreview = alteredLiteral;
        assertThrows(IllegalArgumentException.class, () -> owner.previewMapping(AUTH, scope, binding, 2, fieldId, conversion, sample));
        assertTrue(requests.stream().noneMatch(request -> request.path().endsWith("/mapping-confirmation")));

        var booleanConversion = JSON.createObjectNode().put("method", "parse_boolean");
        booleanConversion.putArray("trueTokens").add("on");
        booleanConversion.putArray("falseTokens").add("off");
        String reviewed = com.voltcanary.vcl.VclSourceMappingReview.digest(digest, source.accessScopeDigest(), fieldId, booleanConversion);
        var appliedDefinition = definition.deepCopy().put("schemaRevision", 3);
        var applied = new com.voltcanary.vcl.VclSourceBindingDescription(binding, account, actor, device, 3, 3,
                UUID.randomUUID(), appliedDefinition,
                com.voltcanary.vcl.VclCanonical.sha256(com.voltcanary.vcl.VclCanonical.encode(appliedDefinition)),
                source.accessScopeDigest(), source.sourceStateDigest(), true, false, source.captureCapabilities());
        mappingReadback = JSON.valueToTree(applied);
        var receipt = JSON.createObjectNode().put("bindingId", binding.toString()).put("deviceId", device.toString())
                .put("fieldId", fieldId.toString()).put("mappingDigest", reviewed).put("schemaRevision", 3)
                .put("schemaSnapshotId", applied.snapshotId().toString()).put("definitionDigest", applied.definitionDigest());
        mappingReceipt = receipt;
        var reviewedMapping = new ReviewedSourceMapping(binding, 2, fieldId, booleanConversion, reviewed);
        booleanConversion.putArray("trueTokens").add("changed after review");
        assertEquals(receipt, reviewedMapping.confirm(owner, AUTH, scope));
        int beforeInspection = requests.size();
        assertEquals(receipt, reviewedMapping.inspect(owner, AUTH, scope));
        assertEquals(List.of("/data/source-bindings/" + binding,
                "/data/source-bindings/" + binding + "/mapping-receipts/" + reviewed,
                "/data/source-bindings/" + binding), requests.subList(beforeInspection, requests.size()).stream().map(Request::path).toList());
        assertTrue(requests.subList(beforeInspection, requests.size()).stream().allMatch(request -> request.method().equals("GET")));
        mappingReceipt = receipt.deepCopy().put("definitionDigest", "f".repeat(64));
        assertThrows(NativeOwnerClient.Uncertain.class,
                () -> reviewedMapping.confirm(owner, AUTH, scope));
    }

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

    @ParameterizedTest @ValueSource(strings = {"RETARGET", "REVIEW_CHANGED", "FIELD_RESERVATION_CHANGED"})
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

    @ParameterizedTest @ValueSource(strings = {"DATASET_CHANGED", "INDEX_METADATA_CHANGED", "CONTRADICTORY_RECEIPT"})
    void succeededReceiptWithoutValidDatasetReadbackRemainsUnknown(String failure) {
        mode = failure;
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

    @Test void malformedVclCannotReachOwnerPreparationOrExecution() {
        var action = NativeAuthoringFixtures.action();
        var parameters = new LinkedHashMap<>(action.parameters());
        var document = new com.fasterxml.jackson.databind.ObjectMapper()
                .<ObjectNode>valueToTree(parameters.get("document"));
        ((ObjectNode) document.path("spec")).put("unknownField", true);
        assertThrows(com.voltcanary.vcl.VclException.class,
                () -> owner.prepare(AUTH, UUID.randomUUID(), document));
        parameters.put("document", document);
        var invalid = new AgentAction(action.id(), action.tool(), action.title(), action.description(), true, parameters);
        assertEquals("FAILED", boundary.run(invalid, new LinkedHashMap<>(), () -> executor.execute(invalid, AUTH)).status());
        assertTrue(requests.isEmpty());
    }

    @Test void registryVersionAndAuthenticationAreCheckedWithoutServiceFallback() {
        assertEquals(NativeAuthoringFixtures.language(), owner.language(AUTH));
        mode = "REGISTRY_CHANGED";
        assertThrows(IllegalArgumentException.class, () -> owner.language(AUTH));
        mode = "REGISTRY_BODY_CHANGED";
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
