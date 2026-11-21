package com.voltcanary.analytics.vcl.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.vcl.ScalarValue;
import com.voltcanary.analytics.vcl.VclWindowPlan;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclCompiler;
import com.voltcanary.vcl.VclDocument;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclObservationProfile;
import com.voltcanary.vcl.VclObservationRead;
import com.voltcanary.vcl.VclParser;
import com.voltcanary.vcl.VclRegistry;
import com.voltcanary.vcl.VclSourceBindingProfile;
import com.voltcanary.vcl.VclUnits;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleCompilationSnapshotsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VclParser PARSER = new VclParser();
    private static final VclCompiler COMPILER = new VclCompiler(new VclRegistry(PARSER), VclUnits.standard());
    private static final UUID ACTOR = UUID.randomUUID(), ACCOUNT = UUID.randomUUID(), RULE = UUID.randomUUID(),
            BINDING = UUID.randomUUID(), GRANT = UUID.randomUUID(), POLICY = UUID.randomUUID(),
            SUBJECT = UUID.randomUUID(), FLAG = UUID.randomUUID();
    private static final String AUTH = "Bearer private-composition-credential";
    private final Map<String, ObjectNode> responses = new LinkedHashMap<>();
    private NativeAsOfOwner asOfOwner;
    private final List<String> paths = new ArrayList<>();
    private HttpServer server;
    private RuleCompilationSnapshots snapshots;
    private ObjectNode source, grant, policy;
    private String grantPath, policyPath, bindingPath;
    private int status;
    private Runnable afterJournalRead = () -> { };
    private UUID repeatedJournalEventId;
    private String journalSubject = "subject";
    private int journalPageSize = 1;
    private String unknownSubjectCheckpoint;
    private String journalRecordedAt = "2026-11-05T00:00:03Z";
    private java.util.function.Consumer<ObjectNode> augmentObservation = row -> { };

    @BeforeEach void start() throws Exception {
        String expires = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS).toString();
        var definition = JSON.createObjectNode().put("id", BINDING.toString()).put("schemaRevision", 1)
                .put("profile", VclSourceBindingProfile.VERSION).put("configurationDigest", "a".repeat(64));
        var fields = definition.putArray("fields");
        fields.addObject().put("id", SUBJECT.toString()).put("label", "Subject").put("type", "TEXT").put("nullable", false);
        fields.addObject().put("id", FLAG.toString()).put("label", "Flag").put("type", "BOOLEAN").put("nullable", true);
        source = JSON.createObjectNode().put("id", BINDING.toString()).put("accountId", ACCOUNT.toString())
                .put("actorId", ACTOR.toString()).put("deviceId", UUID.randomUUID().toString()).put("schemaRevision", 1)
                .put("currentSchemaRevision", 1).put("snapshotId", UUID.randomUUID().toString())
                .put("definitionDigest", hash(definition)).put("accessScopeDigest", "a".repeat(64))
                .put("sourceStateDigest", "b".repeat(64)).put("sourceActive", true).put("ruleExecutionReady", false);
        source.set("definition", definition);
        source.set("captureCapabilities", VclSourceBindingProfile.capabilities());
        var scope = JSON.createObjectNode().put("expiresAt", expires);
        scope.putArray("sourceBindings").addObject().put("id", BINDING.toString()).put("schemaRevision", 1)
                .putArray("fieldIds").add(SUBJECT.toString()).add(FLAG.toString());
        scope.putArray("datasets");
        grant = JSON.createObjectNode().put("id", GRANT.toString()).put("accountId", ACCOUNT.toString()).put("actorId", ACTOR.toString())
                .put("ruleId", RULE.toString()).put("profile", "source-native-fields-grant-1").put("epoch", "1").put("status", "ACTIVE")
                .put("expiresAt", expires).put("definitionDigest", hash(scope));
        grant.set("definition", scope);
        grant.putArray("effects").add("ALERT");
        grant.putArray("deliveryChannels");
        var limits = JSON.createObjectNode().put("mode", "PRIVATE_REFERENCES").put("retentionDays", 7).put("maxObservationReferences", 20);
        policy = JSON.createObjectNode().put("id", POLICY.toString()).put("accountId", ACCOUNT.toString()).put("actorId", ACTOR.toString())
                .put("ruleId", RULE.toString()).put("grantId", GRANT.toString()).put("grantEpoch", "1")
                .put("grantDefinitionDigest", grant.path("definitionDigest").asText()).put("profile", "private-reference-evidence-1")
                .put("revision", 1).put("status", "ACTIVE").put("usable", true).put("expiresAt", expires).put("definitionDigest", hash(limits));
        policy.set("definition", limits);
        grantPath = "/alert-rules/configurable/grants/" + GRANT;
        policyPath = "/alert-rules/configurable/evidence-policies/" + POLICY;
        bindingPath = "/data/source-bindings/" + BINDING + "?schemaRevision=1";
        responses.put(grantPath, grant);
        responses.put(policyPath, policy);
        responses.put(bindingPath, source);
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            paths.add(path);
            assertEquals(AUTH, exchange.getRequestHeaders().getFirst("Authorization"));
            if (path.equals("/data/source-bindings/" + BINDING + "/observations")) {
                assertEquals("POST", exchange.getRequestMethod());
                var request = VclObservationRead.parse(exchange.getRequestBody().readAllBytes());
                byte[] body = observationPage(request).bytes();
                afterJournalRead.run();
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                return;
            }
            boolean post = path.endsWith("/query-asof") || path.endsWith("/query-asof-manifest") || path.contains("/coverage/");
            assertEquals(post ? "POST" : "GET", exchange.getRequestMethod());
            if (post) assertTrue(exchange.getRequestBody().readAllBytes().length > 0);
            var response = responses.get(path);
            byte[] body = response == null ? "{}".getBytes(StandardCharsets.UTF_8) : response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response == null ? 404 : status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var properties = new AnalyticsDownstreamProperties();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.getDeviceService().setBaseUrl(url);
        properties.getAlertService().setBaseUrl(url);
        asOfOwner = new NativeAsOfOwner(JacksonRestClients.builder(), properties);
        snapshots = new RuleCompilationSnapshots(new NativeDatasetSnapshots(JacksonRestClients.builder(), properties),
                new SourceBindingSnapshots(JacksonRestClients.builder(), properties),
                new AutomationGrantSnapshots(JacksonRestClients.builder(), properties),
                new EvidencePolicySnapshots(JacksonRestClients.builder(), properties),
                new SourceCaptureSnapshots(JacksonRestClients.builder(), properties), asOfOwner);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void sharedCompilerUsesAllAuthenticatedOwnersAndPreservesStaticStage() {
        var captured = capture(document());
        var compilation = captured.compile(COMPILER);
        assertEquals("STATIC_TYPED_IR", compilation.stage());
        assertEquals(List.of("read_stream", "sustained", "emit_alert"), compilation.graph().stream().map(node -> node.method()).toList());
        assertEquals(1, compilation.dependencies().size());
        assertEquals(BINDING, compilation.dependencies().getFirst().id());
        assertEquals(hash(source.path("definition")), compilation.dependencies().getFirst().snapshotDigest());
        assertTrue(paths.containsAll(List.of(bindingPath, grantPath, policyPath)));
        assertEquals(VclCanonical.documentDigest(document()), compilation.documentDigest());
        assertFalse(captured.toString().contains("private-composition"));
        assertFalse(compilation.toString().contains("private-composition"));
        var again = captured.compile(COMPILER);
        assertEquals(compilation, again);
    }

    @Test void revokedOrChangedGrantCannotReuseCapturedMetadata() {
        var captured = capture(document());
        grant.put("status", "REVOKED").put("epoch", "2");
        code("AUTOMATION_GRANT_INACTIVE", () -> captured.compile(COMPILER));
        grant.put("status", "ACTIVE");
        code("AUTOMATION_GRANT_CHANGED", () -> captured.compile(COMPILER));
    }

    @Test void journalCompilationPinsOriginalCutAcrossHeadAdvances() {
        var journal = journalResponse();
        var staticCompilation = capture(document()).compile(COMPILER);
        paths.clear();
        var captured = captureJournal();
        var compiled = captured.compile(COMPILER);
        assertEquals("STATIC_TYPED_IR", compiled.stage());
        assertEquals(staticCompilation.dependencies(), compiled.dependencies());
        assertNotEquals(staticCompilation.accessScopeDigest(), compiled.accessScopeDigest());
        assertFalse(paths.contains(bindingPath));

        journal.put("headCheckpoint", "6");
        assertEquals(compiled, captured.compile(COMPILER));
        assertNotEquals(compiled.accessScopeDigest(), captureJournal().compile(COMPILER).accessScopeDigest());
        journal.put("headCheckpoint", "4");
        code("CAPTURE_CHECKPOINT_REGRESSED", () -> captured.compile(COMPILER));
    }

    @Test void journalCompilationFailsClosedOnUnavailableCutGenerationChangeAndGrantRevocation() {
        code("SOURCE_CAPTURE_UNAVAILABLE", this::captureJournal);
        var journal = journalResponse();
        var captured = captureJournal();
        String generation = journal.path("inputGeneration").asText();
        journal.put("inputGeneration", UUID.randomUUID().toString());
        code("CAPTURE_GENERATION_CHANGED", () -> captured.compile(COMPILER));
        journal.put("inputGeneration", generation);
        grant.put("status", "REVOKED").put("epoch", "2");
        code("AUTOMATION_GRANT_INACTIVE", () -> captured.compile(COMPILER));
    }

    @Test void journalCompilationEnforcesTheSameExactFieldGrant() {
        journalResponse();
        ((com.fasterxml.jackson.databind.node.ArrayNode) grant.at("/definition/sourceBindings/0/fieldIds")).remove(1);
        grant.put("definitionDigest", hash(grant.path("definition")));
        policy.put("grantDefinitionDigest", grant.path("definitionDigest").asText());
        var captured = captureJournal();
        code("AUTOMATION_GRANT_SCOPE_DENIED", () -> captured.compile(COMPILER));
    }

    @Test void runtimeSourceCheckRequiresActualReadinessAndFreshGrant() {
        code("SOURCE_CAPTURE_UNAVAILABLE", () -> capture(document()).compileWithReadySources(COMPILER));
        journalResponse();
        var captured = captureJournal();
        assertEquals("STATIC_TYPED_IR", captured.compile(COMPILER).stage());
        code("SOURCE_RUNTIME_UNAVAILABLE", () -> captured.compileWithReadySources(COMPILER));
        grant.put("status", "REVOKED").put("epoch", "2");
        code("AUTOMATION_GRANT_INACTIVE", () -> captured.compileWithReadySources(COMPILER));
    }

    @Test void journalInputBudgetBoundsOriginalCheckpointSpan() {
        var journal = journalResponse();
        journal.put("generationStartCheckpoint", "2");
        code("JOURNAL_INPUT_BUDGET_INVALID", () -> snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, document(), 0));
        assertTrue(paths.isEmpty());
        code("JOURNAL_INPUT_BUDGET_EXCEEDED", () -> snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, document(), 2));
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, document(), 3);
        journal.put("headCheckpoint", Long.toString(Long.MAX_VALUE));
        assertEquals("STATIC_TYPED_IR", captured.compile(COMPILER).stage());
        code("JOURNAL_INPUT_BUDGET_EXCEEDED",
                () -> snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, document(), Long.MAX_VALUE - 3));
    }

    @Test void journalInputBudgetCountsEachBindingOnceAndCannotOverflow() {
        var first = journalResponse();
        first.put("headCheckpoint", Long.toString(Long.MAX_VALUE));
        var json = document().json();
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) json.at("/spec/nodes");
        var extra = (ObjectNode) nodes.get(0).deepCopy();
        extra.put("id", "other_source");
        nodes.add(extra);
        snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, PARSER.parse(json.toString()), Long.MAX_VALUE);

        UUID otherId = UUID.randomUUID();
        var other = responses.get("/data/source-bindings/" + BINDING + "/capture?schemaRevision=1").deepCopy();
        ((ObjectNode) extra.path("config")).put("bindingId", otherId.toString());
        var otherSource = (ObjectNode) other.path("source");
        otherSource.put("id", otherId.toString());
        var definition = (ObjectNode) otherSource.path("definition");
        definition.put("id", otherId.toString());
        String digest = hash(definition);
        otherSource.put("definitionDigest", digest);
        ((ObjectNode) other.path("capture")).put("headCheckpoint", "1");
        ((ObjectNode) other.at("/capture/pin")).put("bindingId", otherId.toString()).put("definitionDigest", digest);
        responses.put("/data/source-bindings/" + otherId + "/capture?schemaRevision=1", other);
        code("JOURNAL_INPUT_BUDGET_EXCEEDED",
                () -> snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, PARSER.parse(json.toString()), Long.MAX_VALUE));
    }

    private RuleCompilationSnapshot captureJournal() {
        return snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, document(), 10);
    }

    @Test void publicCompilationPersistsOwnerValidatedDirectBundles() throws Exception {
        var definition = (ObjectNode) source.path("definition");
        definition.putObject("sampleHold").put("version", "bounded-sample-hold-1").put("maxHoldUs", "2000000");
        var startField = UUID.randomUUID().toString();
        var endField = UUID.randomUUID().toString();
        definition.putObject("intervalReport").put("version", "interval-end-event-time-1")
                .put("startKey", "start").put("endKey", "end").put("maxSpanUs", "10000000");
        var mappings = definition.putArray("fieldMappings");
        for (var key : List.of("start", "end")) {
            var id = key.equals("start") ? startField : endField;
            ((com.fasterxml.jackson.databind.node.ArrayNode) definition.path("fields")).addObject()
                    .put("id", id).put("label", key).put("type", "INSTANT").put("nullable", true);
            mappings.addObject().put("sourceKey", key).put("sourceType", "instant").put("fieldId", id).put("vclReady", true);
            ((com.fasterxml.jackson.databind.node.ArrayNode) grant.at("/definition/sourceBindings/0/fieldIds")).add(id);
        }
        grant.put("definitionDigest", hash(grant.path("definition")));
        policy.put("grantDefinitionDigest", grant.path("definitionDigest").textValue());
        source.put("definitionDigest", hash(definition));
        var journal = journalResponse();
        UUID proof = UUID.randomUUID();
        var from = Instant.parse("2026-11-05T00:00:00Z");
        var until = from.plusSeconds(5);
        var coverage = JSON.createObjectNode().put("proofId", proof.toString())
                .put("inputGeneration", journal.path("inputGeneration").textValue())
                .put("snapshotId", journal.at("/pin/snapshotId").textValue())
                .put("coverage", "COVERED").putNull("reason").put("requiredCheckpoint", "5")
                .put("acceptedAt", until.toString());
        coverage.putObject("evidence").put("proofId", proof.toString())
                .put("inputGeneration", journal.path("inputGeneration").textValue())
                .put("from", from.toString()).put("until", until.toString()).put("eventTimeBasis", "SOURCE_EVENT_TIME_V1")
                .putObject("position").put("epoch", UUID.randomUUID().toString()).put("sequence", "5");
        responses.put("/data/source-bindings/" + BINDING + "/coverage/" + proof, coverage);
        var database = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        TemporalRuntimeContract.migrate(database);
        var properties = new AnalyticsDownstreamProperties();
        properties.getDeviceService().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getAlertService().setBaseUrl(properties.getDeviceService().getBaseUrl());
        var service = new ReviewedRuleCutovers(database, snapshots, JacksonRestClients.builder(), properties);
        var request = JSON.createObjectNode();
        request.set("document", document().json());
        request.putObject("readSet").put("account", ACCOUNT.toString()).put("binding", BINDING.toString())
                .put("schemaRevision", 1).put("proofId", proof.toString()).put("from", from.toString()).put("boundary", until.toString());
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.voltcanary.analytics.controller.ReviewedRuleCutoversController(service))
                .setMessageConverters(new org.springframework.http.converter.ByteArrayHttpMessageConverter(),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                                JSON.copy().findAndRegisterModules())).build();
        var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/analytics/configurable-rules/compilations")
                .header("Authorization", AUTH).header("X-User-Id", ACTOR)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var response = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var receipt = PARSER.readJson(response.getContentAsByteArray());
        UUID reviewId = UUID.randomUUID();
        var apply = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/analytics/configurable/rule-cutovers/{reviewId}/apply", reviewId)
                .header("Authorization", AUTH).header("X-User-Id", ACTOR)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var substituted = ((ObjectNode) receipt.path("checkpoint")).deepCopy();
        substituted.set("document", request.path("document"));
        var invalidApply = http.perform(apply.content(substituted.toString())).andReturn().getResponse();
        assertEquals(400, invalidApply.getStatus(), invalidApply.getContentAsString());
        assertEquals("RULE_APPLY_REQUEST_INVALID", PARSER.readJson(invalidApply.getContentAsByteArray()).path("code").asText());
        var intent = new com.voltcanary.vcl.VclRuleCutoverIntent(RULE, GRANT, null, UUID.randomUUID(),
                UUID.fromString(receipt.path("bundleId").asText()), receipt.path("bundleDigest").asText(),
                receipt.at("/checkpoint/digest").asText());
        var confirmed = JSON.createObjectNode().put("id", reviewId.toString()).put("accountId", ACCOUNT.toString())
                .put("actorId", ACTOR.toString()).put("grantEpoch", 1).put("confirmedAt", until.toString())
                .put("reviewDigest", intent.reviewDigest(ACCOUNT, ACTOR, reviewId, 1));
        confirmed.set("intent", JSON.valueToTree(intent));
        responses.put("/alert-rules/configurable/cutover-reviews/" + reviewId + "/confirmed", confirmed);
        var stale = ((ObjectNode) receipt.path("checkpoint")).deepCopy().put("digest", "0".repeat(64));
        var staleApply = http.perform(apply.content(stale.toString())).andReturn().getResponse();
        assertEquals(409, staleApply.getStatus(), staleApply.getContentAsString());
        assertEquals("START_CHECKPOINT_CHANGED", PARSER.readJson(staleApply.getContentAsByteArray()).path("code").asText());
        journalRecordedAt = from.plusSeconds(6).toString();
        augmentObservation = row -> {
            var values = (com.fasterxml.jackson.databind.node.ArrayNode) row.path("fields");
            unknownField(values, startField, "INSTANT");
            unknownField(values, endField, "INSTANT");
        };
        var sustainedPreview = rulePreview(http, "window-preview", receipt, "condition", from, until, null, null);
        assertEquals("OK", sustainedPreview.at("/result/status").textValue());
        assertTrue(sustainedPreview.at("/result/literal/value").booleanValue(), sustainedPreview.toString());
        assertEquals(2, sustainedPreview.at("/result/evidence").size());
        UUID bundleId = UUID.fromString(receipt.path("bundleId").textValue());
        var saved = new JdbcCompilationBundleStore(database).find(ACCOUNT, bundleId).orElseThrow();
        assertEquals(receipt.path("bundleDigest").textValue(), saved.digest());
        var replay = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(200, replay.getStatus(), replay.getContentAsString());
        assertEquals(receipt, PARSER.readJson(replay.getContentAsByteArray()));
        assertEquals(receipt.at("/checkpoint/digest").textValue(), saved.json().at("/ownerValidation/execution/snapshotSetDigest").textValue());
        request.set("document", windowRule().json());
        var window = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(200, window.getStatus(), window.getContentAsString());
        var windowReceipt = PARSER.readJson(window.getContentAsByteArray());
        var aggregatePreview = rulePreview(http, "window-preview", windowReceipt, "aggregate", from, until, null, null);
        assertEquals("OK", aggregatePreview.at("/result/status").textValue());
        assertEquals("4", aggregatePreview.at("/result/literal/value").textValue());
        var savedWindow = new JdbcCompilationBundleStore(database)
                .find(ACCOUNT, UUID.fromString(windowReceipt.path("bundleId").textValue())).orElseThrow();
        assertEquals(windowReceipt.path("bundleDigest").textValue(), savedWindow.digest());
        assertEquals(3L * com.voltcanary.analytics.vcl.KeyedStateStore.MAX_STATE_BYTES,
                savedWindow.json().at("/ownerValidation/cost/stateBytes").longValue());
        var interval = windowRule().json();
        var operator = (ObjectNode) interval.at("/spec/nodes/2");
        operator.put("method", "interval_union_window");
        var measure = operator.at("/config/measureFieldId").textValue();
        var config = operator.putObject("config").put("subjectFieldId", SUBJECT.toString())
                .put("startFieldId", startField).put("endFieldId", endField).put("timezone", "UTC")
                .put("window", "LOCAL_DAY").put("emit", "ON_CLOSE").put("measureFieldId", measure);
        config.putObject("includeWhere").put("op", "FIELD").put("fieldId", FLAG.toString());
        config.putObject("excludeWhere").put("op", "CALL").put("method", "not").putArray("args")
                .addObject().put("op", "FIELD").put("fieldId", FLAG.toString());
        ((ObjectNode) interval.at("/spec/nodes/3/config/predicate/args/1/literal")).put("type", "DURATION");
        request.set("document", interval);
        var intervalResponse = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(200, intervalResponse.getStatus(), intervalResponse.getContentAsString());
        augmentObservation = row -> {
            int checkpoint = row.path("checkpoint").asInt();
            var values = (com.fasterxml.jackson.databind.node.ArrayNode) row.path("fields");
            ((ObjectNode) values.get(1)).put("value", checkpoint != 3);
            int[] startOffsets = {0, 0, 2, 1, 4};
            instantField(values, startField, from.plusSeconds(startOffsets[checkpoint - 1]));
            instantField(values, endField, from.plusSeconds(checkpoint));
        };
        var intervalReceipt = PARSER.readJson(intervalResponse.getContentAsByteArray());
        var workDuration = rulePreview(http, "window-preview", intervalReceipt, "aggregate", from,
                from.plusSeconds(86_400), null, null);
        assertEquals("OK", workDuration.at("/result/status").textValue());
        assertEquals("DURATION", workDuration.at("/result/literal/type").textValue());
        assertEquals("4000000", workDuration.at("/result/literal/value").textValue());
        var session = document().json();
        var sessionNodes = (com.fasterxml.jackson.databind.node.ArrayNode) session.at("/spec/nodes");
        sessionNodes.remove(1);
        ((ObjectNode) sessionNodes.get(1).path("inputs")).put("decisions", "absence");
        var opening = sessionNodes.addObject().put("id", "session").put("method", "window_session");
        opening.putObject("inputs").put("events", "source");
        var sessionConfig = opening.putObject("config").put("subjectFieldId", SUBJECT.toString())
                .put("occurrenceFieldId", SUBJECT.toString()).put("maxDurationUs", "10000000");
        sessionConfig.putObject("startWhen").put("op", "FIELD").put("fieldId", FLAG.toString());
        sessionConfig.putObject("closeWhen").put("op", "CONST").putObject("literal")
                .put("type", "BOOLEAN").put("value", true);
        var witness = sessionNodes.addObject().put("id", "witness").put("method", "observed_within");
        witness.putObject("inputs").put("sessions", "session");
        witness.putObject("config").put("minimumDurationUs", "1000000")
                .putObject("predicate").put("op", "FIELD").put("fieldId", FLAG.toString());
        var absence = sessionNodes.addObject().put("id", "absence").put("method", "absent_within");
        absence.putObject("inputs").put("sessions", "session").put("witnesses", "witness");
        absence.putObject("config").put("allowedLatenessUs", "1000000").put("onUnknown", "UNKNOWN");
        request.set("document", session);
        var sessionResponse = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(200, sessionResponse.getStatus(), sessionResponse.getContentAsString());
        var sessionReceipt = PARSER.readJson(sessionResponse.getContentAsByteArray());
        var savedSession = new JdbcCompilationBundleStore(database)
                .find(ACCOUNT, UUID.fromString(sessionReceipt.path("bundleId").textValue())).orElseThrow();
        assertEquals("vcl-durable-window-session-1",
                savedSession.json().at("/ownerValidation/execution/operatorVersions/window_session").textValue());
        var invalidGraph = request.deepCopy();
        var invalidNodes = (com.fasterxml.jackson.databind.node.ArrayNode) invalidGraph.at("/document/spec/nodes");
        var otherSession = opening.deepCopy().put("id", "other_session");
        invalidNodes.add(otherSession);
        assertCompilationRejected(http, post, invalidGraph, "GRAPH_UNREACHABLE");
        for (var node : invalidNodes) {
            if ("observed_within".equals(node.path("method").textValue())) {
                ((ObjectNode) node.path("inputs")).put("sessions", "other_session");
            }
        }
        assertCompilationRejected(http, post, invalidGraph, "SESSION_MISMATCH");
        UUID zone = UUID.randomUUID();
        UUID zoneField = UUID.randomUUID();
        UUID position = UUID.randomUUID();
        UUID occurrence = UUID.randomUUID();
        var fields = (com.fasterxml.jackson.databind.node.ArrayNode) definition.path("fields");
        fields.addObject().put("id", zoneField.toString()).put("label", "Zone").put("type", "REF").put("nullable", false)
                .putObject("target").put("kind", "RESOURCE").put("resourceKind", "SPATIAL_FEATURE");
        fields.addObject().put("id", position.toString()).put("label", "Position").put("type", "POINT").put("nullable", false);
        fields.addObject().put("id", occurrence.toString()).put("label", "Occurrence").put("type", "TEXT").put("nullable", false);
        mappings.addObject().put("sourceKey", "subject").put("sourceType", "string").put("fieldId", SUBJECT.toString()).put("vclReady", true);
        mappings.addObject().put("sourceKey", "occurrence").put("sourceType", "string").put("fieldId", occurrence.toString()).put("vclReady", true);
        definition.putObject("occurrenceRouting").put("version", "direct-occurrence-1")
                .put("subjectKey", "subject").put("occurrenceKey", "occurrence");
        var grantFields = (com.fasterxml.jackson.databind.node.ArrayNode) grant.at("/definition/sourceBindings/0/fieldIds");
        for (var id : List.of(zoneField, position, occurrence)) grantFields.add(id.toString());
        grant.put("definitionDigest", hash(grant.path("definition")));
        policy.put("grantDefinitionDigest", grant.path("definitionDigest").textValue());
        source.put("definitionDigest", hash(definition));
        ((ObjectNode) journal.path("pin")).put("definitionDigest", source.path("definitionDigest").textValue());
        opening.put("method", "zone_session").putObject("config")
                .put("subjectFieldId", SUBJECT.toString()).put("occurrenceFieldId", occurrence.toString())
                .put("zoneFieldId", zoneField.toString()).put("positionFieldId", position.toString())
                .putNull("accuracyFieldId").put("uncertaintyFloorMetres", 5).put("exitDebounceUs", "1000000")
                .put("maxDurationUs", "10000000").put("minimumExitObservations", 2).put("accuracyFallback", "APPROVED_FLOOR");
        ((ObjectNode) witness.path("config")).put("minimumDurationUs", "0");
        var circle = com.voltcanary.vcl.VclCircleRevision.create(zone, ACCOUNT, 1,
                new com.voltcanary.vcl.VclCircleRevision.Definition(24, 60, "100"));
        var circlePath = "/data/spatial-features/" + zone + "/circle?revision=1";
        responses.put(circlePath, JSON.valueToTree(circle));
        ((ObjectNode) request.path("readSet")).putArray("circles").addObject().put("id", zone.toString()).put("revision", 1);
        var zoneResponse = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(200, zoneResponse.getStatus(), zoneResponse.getContentAsString());
        var zoneReceipt = PARSER.readJson(zoneResponse.getContentAsByteArray());
        UUID zoneBundle = UUID.fromString(zoneReceipt.path("bundleId").textValue());
        var savedZone = new JdbcCompilationBundleStore(database).find(ACCOUNT, zoneBundle).orElseThrow();
        assertTrue(savedZone.payload().contains(circle.digest()));
        var circleImpact = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/analytics/configurable-rules/circles/{id}/dependencies", zone)
                .param("revision", "1").header("Authorization", AUTH);
        var impactResponse = http.perform(circleImpact.header("X-User-Id", ACTOR)).andReturn().getResponse();
        assertEquals(200, impactResponse.getStatus(), impactResponse.getContentAsString());
        var impact = PARSER.readJson(impactResponse.getContentAsByteArray());
        assertTrue(impact.path("complete").asBoolean());
        assertEquals(1, impact.path("dependencies").size());
        assertEquals(zoneBundle.toString(), impact.at("/dependencies/0/id").textValue());
        responses.put(circlePath, JSON.valueToTree(com.voltcanary.vcl.VclCircleRevision.create(UUID.randomUUID(), ACCOUNT, 1,
                new com.voltcanary.vcl.VclCircleRevision.Definition(24, 60, "100"))));
        var foreignImpact = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/analytics/configurable-rules/circles/{id}/dependencies", zone)
                .param("revision", "1").header("Authorization", AUTH).header("X-User-Id", ACTOR)).andReturn().getResponse();
        assertEquals(409, foreignImpact.getStatus());
        responses.remove(circlePath);
        var unavailableImpact = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/analytics/configurable-rules/circles/{id}/dependencies", zone)
                .param("revision", "1").header("Authorization", AUTH).header("X-User-Id", ACTOR)).andReturn().getResponse();
        assertEquals(404, unavailableImpact.getStatus());
        responses.put(circlePath, JSON.valueToTree(circle));
        var preview = new ReviewedRuleCutovers.Preview(ACCOUNT, zoneBundle, from, until,
                List.of(new RuleCompilationSnapshot.SourceStartProof(BINDING, 1, proof)), Map.of());
        var previewed = service.preview(AUTH, ACTOR, preview);
        var returned = JSON.copy().findAndRegisterModules().treeToValue(zoneReceipt.path("checkpoint"),
                RuleCompilationSnapshots.StartCheckpoint.class);
        assertEquals(previewed, returned);
        augmentObservation = row -> {
            long checkpoint = row.path("checkpoint").asLong();
            var values = (com.fasterxml.jackson.databind.node.ArrayNode) row.path("fields");
            ((ObjectNode) values.get(1)).put("value", checkpoint == 3);
            unknownField(values, startField, "INSTANT");
            unknownField(values, endField, "INSTANT");
            values.addObject().put("fieldId", occurrence.toString()).put("type", "TEXT")
                    .put("knowledge", "KNOWN").putNull("reason").put("value", "trip-1");
            values.addObject().put("fieldId", zoneField.toString()).put("type", "REF")
                    .put("knowledge", "KNOWN").putNull("reason").put("value", zone.toString())
                    .putObject("target").put("kind", "RESOURCE").put("resourceKind", "SPATIAL_FEATURE");
            values.addObject().put("fieldId", position.toString()).put("type", "POINT")
                    .put("knowledge", "KNOWN").putNull("reason").putObject("value")
                    .put("longitude", checkpoint == 2 || checkpoint == 3 ? 24.0 : 24.01).put("latitude", 60.0);
        };
        var visitPreview = rulePreview(http, "session-preview", zoneReceipt, "absence", from, until.plusSeconds(1), zone, "trip-1");
        assertEquals("OK", visitPreview.path("status").textValue());
        assertFalse(visitPreview.at("/literal/value").booleanValue());
        assertTrue(visitPreview.path("firstWitness").isTextual());
        assertEquals(1, visitPreview.path("positionWitnesses").size());
        var noVisit = rulePreview(http, "session-preview", zoneReceipt, "absence", from, until.plusSeconds(1), zone, "never-entered");
        assertEquals("UNKNOWN", noVisit.path("status").textValue());
        assertEquals("SESSION_NOT_OBSERVED_CLOSED", noVisit.path("reason").textValue());
        assertTrue(noVisit.path("exit").isNull());
        var unsupported = request.deepCopy();
        var graph = (com.fasterxml.jackson.databind.node.ArrayNode) unsupported.at("/document/spec/nodes");
        for (var node : graph) {
            if ("zone_session".equals(node.path("method").textValue())) {
                ((ObjectNode) node).put("method", "expectation_event");
            }
        }
        var rejected = http.perform(post.content(unsupported.toString())).andReturn().getResponse();
        assertEquals(409, rejected.getStatus());
        assertEquals("GRAMMAR", PARSER.readJson(rejected.getContentAsByteArray()).path("code").textValue());
        verifyResumeBoundary(database, service, request, savedZone, returned, coverage);
        responses.put(circlePath, JSON.valueToTree(com.voltcanary.vcl.VclCircleRevision.create(zone, ACCOUNT, 1,
                new com.voltcanary.vcl.VclCircleRevision.Definition(24, 60, "200"))));
        code("SPATIAL_REVISION_CHANGED", () -> service.preview(AUTH, ACTOR, preview));
        journal.put("headCheckpoint", "4097");
        var denied = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(409, denied.getStatus());
        assertEquals("JOURNAL_INPUT_BUDGET_EXCEEDED", PARSER.readJson(denied.getContentAsByteArray()).path("code").textValue());
        ((ObjectNode) request.path("readSet")).put("unknown", true);
        paths.clear();
        var malformed = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(400, malformed.getStatus());
        assertEquals("RULE_COMPILATION_REQUEST_INVALID",
                PARSER.readJson(malformed.getContentAsByteArray()).path("code").textValue());
        assertTrue(paths.isEmpty());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(database);
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM vcl_compilation_bundles", Integer.class));
    }

    private void verifyResumeBoundary(javax.sql.DataSource database, ReviewedRuleCutovers service, ObjectNode request,
            com.voltcanary.analytics.vcl.CompilationBundle bundle, RuleCompilationSnapshots.StartCheckpoint checkpoint,
            ObjectNode coverage) {
        var deployments = new JdbcRuleDeployments(database, new JdbcCompilationBundleStore(database));
        var initial = activate(deployments, bundle, checkpoint);
        assertEquals(JdbcRuleDeployments.State.PAUSED, deployments.pause(ACCOUNT, initial.epoch(), value -> { }).state());
        code("DEPLOYMENT_RESUME_BOUNDARY_INVALID", () -> activate(deployments, bundle, checkpoint));
        var boundary = new JdbcKeyedStateStore(database).databaseTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var from = boundary.minusSeconds(1);
        UUID proofId = UUID.randomUUID();
        var fresh = coverage.deepCopy().put("proofId", proofId.toString()).put("acceptedAt", boundary.toString());
        ((ObjectNode) fresh.path("evidence")).put("proofId", proofId.toString())
                .put("from", from.toString()).put("until", boundary.toString());
        responses.put("/data/source-bindings/" + BINDING + "/coverage/" + proofId, fresh);
        var resumeRequest = request.deepCopy();
        ((ObjectNode) resumeRequest.path("readSet")).put("proofId", proofId.toString())
                .put("from", from.toString()).put("boundary", boundary.toString());
        var receipt = service.compile(AUTH, ACTOR, resumeRequest.toString().getBytes(StandardCharsets.UTF_8));
        var resumedBundle = new JdbcCompilationBundleStore(database).find(ACCOUNT, receipt.bundleId()).orElseThrow();
        var resumed = activate(deployments, resumedBundle, receipt.checkpoint());
        assertEquals(JdbcRuleDeployments.State.ACTIVE, resumed.state());
        assertNotEquals(initial.epoch(), resumed.epoch());
        assertTrue(resumed.resumeFrom().isAfter(boundary));
        assertFalse(resumed.includesSessionObservation(boundary));
        assertFalse(resumed.includesSessionObservation(resumed.resumeFrom().minus(1, java.time.temporal.ChronoUnit.MICROS)));
        assertTrue(resumed.includesSessionObservation(resumed.resumeFrom()));
        assertEquals(JdbcRuleDeployments.State.PAUSED, deployments.find(ACCOUNT, initial.epoch()).orElseThrow().state());
    }

    private static JdbcRuleDeployments.Deployment activate(JdbcRuleDeployments deployments,
            com.voltcanary.analytics.vcl.CompilationBundle bundle, RuleCompilationSnapshots.StartCheckpoint checkpoint) {
        var epoch = UUID.randomUUID();
        var staged = deployments.stage(ACTOR, bundle, epoch);
        var start = new JdbcRuleDeployments.CutoverStart(null, checkpoint.source().boundary(), checkpoint.digest());
        var recipe = RuleStartCheckpointRecipe.from(checkpoint);
        var pending = deployments.cutover(ACCOUNT, epoch, start, recipe, (previous, next, boundary) -> { }, false);
        deployments.acknowledgeEvaluatorReadiness(pending, new JdbcRuleDeployments.EvaluatorReadiness(
                staged.bundleDigest(), checkpoint.digest(), "a".repeat(64)));
        return deployments.cutover(ACCOUNT, epoch, start, recipe, (previous, next, boundary) -> { }, true);
    }

    @Test void coverageCannotAdvancePastTheOriginalCapturedCut() {
        var journal = journalResponse();
        UUID proof = UUID.randomUUID();
        var covered = JSON.createObjectNode().put("proofId", proof.toString())
                .put("inputGeneration", journal.path("inputGeneration").textValue())
                .put("snapshotId", journal.at("/pin/snapshotId").textValue())
                .put("coverage", "COVERED").putNull("reason").put("requiredCheckpoint", "6");
        var evidence = covered.putObject("evidence").put("proofId", proof.toString())
                .put("inputGeneration", covered.path("inputGeneration").textValue())
                .put("from", "2026-11-05T00:00:00Z").put("until", "2026-11-05T00:00:06Z");
        evidence.putObject("position").put("epoch", UUID.randomUUID().toString()).put("sequence", "6");
        String path = "/data/source-bindings/" + BINDING + "/coverage/" + proof;
        responses.put(path, covered);
        var captured = captureJournal();
        journal.put("headCheckpoint", "6");
        var from = Instant.parse("2026-11-05T00:00:00Z");
        var until = from.plusSeconds(5);
        assertEquals("PROOF_OUTSIDE_CAPTURED_CUT", captured.coverage(BINDING, 1, proof, from, until).reason());
        covered.put("requiredCheckpoint", "5");
        assertEquals("COVERED", captured.coverage(BINDING, 1, proof, from, until).coverage());
        assertEquals("UNKNOWN", captured.coverage(BINDING, 1, proof, from, until.plusSeconds(2)).coverage());
        grant.put("status", "REVOKED").put("epoch", "2");
        paths.clear();
        code("AUTOMATION_GRANT_INACTIVE", () -> captured.coverage(BINDING, 1, proof, from, until));
        assertFalse(paths.contains(path));
    }

    @Test void windowBatchUsesCheckedPlanAndOriginalCutForMembershipBudget() {
        var journal = journalResponse();
        var rule = windowRule();
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, rule, 5);
        code("WINDOW_JOURNAL_REQUIRED", () -> capture(rule).prepareJournalWindowBatch(COMPILER, "aggregate", 15));
        code("WINDOW_MEMBERSHIP_BUDGET_INVALID", () -> captured.prepareJournalWindowBatch(COMPILER, "aggregate", 0));
        code("WINDOW_OPERATOR_REQUIRED", () -> captured.prepareJournalWindowBatch(COMPILER, "source", 15));
        code("WINDOW_MEMBERSHIP_BUDGET_EXCEEDED", () -> captured.prepareJournalWindowBatch(COMPILER, "aggregate", 14));
        var prepared = captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15);
        assertEquals(15, prepared.maximumMemberships());
        assertEquals(3, prepared.plan().windows().maximumFanout());
        journal.put("headCheckpoint", "4097");
        assertEquals(prepared.plan().digest(), captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15).plan().digest());
        var later = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, rule, 4097);
        code("WINDOW_BATCH_INPUT_LIMIT", () -> later.prepareJournalWindowBatch(COMPILER, "aggregate", Long.MAX_VALUE));
        grant.put("status", "REVOKED").put("epoch", "2");
        code("AUTOMATION_GRANT_INACTIVE", () -> captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15));
    }

    private VclDocument windowRule() {
        var json = document().json();
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) json.at("/spec/nodes");
        nodes.remove(1);
        ((ObjectNode) nodes.get(1).path("inputs")).put("decisions", "compare");
        UUID measure = UUID.randomUUID();
        var aggregate = nodes.addObject().put("id", "aggregate").put("method", "window_aggregate");
        aggregate.putObject("inputs").put("events", "source");
        var config = aggregate.putObject("config").put("subjectFieldId", SUBJECT.toString()).put("reducer", "COUNT")
                .put("measureFieldId", measure.toString()).put("emit", "ON_CLOSE").putNull("valueFieldId");
        config.putObject("window").put("kind", "ROLLING").put("sizeUs", "5000000")
                .put("stepUs", "2000000").put("alignment", "1970-01-01T00:00:00Z");
        var compare = nodes.addObject().put("id", "compare").put("method", "compare");
        compare.putObject("inputs").put("measures", "aggregate");
        var args = compare.putObject("config").putObject("predicate").put("op", "CALL").put("method", "gt").putArray("args");
        args.addObject().put("op", "FIELD").put("fieldId", measure.toString());
        args.addObject().put("op", "CONST").putObject("literal").put("type", "INT64").put("value", "0");
        return PARSER.parse(json.toString());
    }

    @Test void windowBatchAppliesFiltersBeforeCountingJournalInput() {
        journalResponse();
        var json = windowRule().json();
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) json.at("/spec/nodes");
        ((ObjectNode) nodes.get(2).path("inputs")).put("events", "filtered");
        var filter = nodes.addObject().put("id", "filtered").put("method", "filter");
        filter.putObject("inputs").put("events", "source");
        filter.putObject("config").putObject("predicate").put("op", "FIELD").put("fieldId", FLAG.toString());
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, PARSER.parse(json.toString()), 5);
        var window = new VclWindowPlan.Window(Instant.parse("2026-11-05T00:00:00Z"), Instant.parse("2026-11-05T00:00:05Z"));
        assertEquals("4", captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15)
                .preview("subject", window, 100_000).localValue().literal().path("value").textValue());
        var predicate = ((ObjectNode) filter.path("config")).putObject("predicate").put("op", "CONST");
        predicate.putObject("literal").put("type", "BOOLEAN").put("value", false);
        var excluded = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, PARSER.parse(json.toString()), 5);
        assertEquals("0", excluded.prepareJournalWindowBatch(COMPILER, "aggregate", 15)
                .preview("subject", window, 100_000).localValue().literal().path("value").textValue());
    }

    @Test void preparedWindowReadsOriginalPrefixAndRechecksGrantAfterIo() {
        var journal = journalResponse();
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, windowRule(), 5);
        var batch = captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15);
        journal.put("headCheckpoint", "7");
        var page = batch.readPage("0", 1);
        assertEquals("5", page.request().throughCheckpoint());
        assertEquals("1", page.nextCheckpoint());
        assertEquals("UNKNOWN", page.upstreamCoverage());
        assertFalse(page.ruleExecutionReady());
        assertTrue(page.observations().getFirst().at("/fields/1/value").booleanValue());
        afterJournalRead = () -> grant.put("status", "REVOKED").put("epoch", "2");
        code("AUTOMATION_GRANT_INACTIVE", () -> batch.readPage("1", 1));
        paths.clear();
        code("AUTOMATION_GRANT_INACTIVE", () -> batch.readPage("1", 1));
        assertFalse(paths.contains("/data/source-bindings/" + BINDING + "/observations"));
    }

    @Test void materializesOnlyThePinnedLocalPrefixWithinAnExactByteBudget() {
        var journal = journalResponse();
        journal.put("generationStartCheckpoint", "2");
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, windowRule(), 3);
        var batch = captured.prepareJournalWindowBatch(COMPILER, "aggregate", 9);
        journal.put("headCheckpoint", "8");
        var retained = batch.readRetainedInput(100_000);
        assertEquals(3, retained.observationCount());
        assertEquals(List.of("3", "4", "5"), retained.pages().stream().map(VclObservationPage::nextCheckpoint).toList());
        assertEquals("2", retained.pages().getFirst().request().afterCheckpoint());
        assertEquals("5", retained.pages().getLast().request().throughCheckpoint());
        assertFalse(retained.pages().getLast().hasMore());
        assertTrue(retained.pages().stream().allMatch(page -> page.upstreamCoverage().equals("UNKNOWN")
                && !page.ruleExecutionReady() && !page.negativeEvidenceReady()));
        var atLimit = batch.readRetainedInput(retained.encodedBytes());
        assertEquals(retained.cutDigest(), atLimit.cutDigest());
        assertEquals(retained.inputDigest(), atLimit.inputDigest());
        assertEquals(retained.encodedBytes(), atLimit.encodedBytes());
        journalPageSize = 3;
        var repaged = batch.readRetainedInput(100_000);
        assertEquals(1, repaged.pages().size());
        assertEquals(retained.inputDigest(), repaged.inputDigest());
        journalPageSize = 1;
        code("WINDOW_INPUT_BYTE_BUDGET_EXCEEDED", () -> batch.readRetainedInput(retained.encodedBytes() - 1));
        paths.clear();
        code("WINDOW_INPUT_BYTE_BUDGET_INVALID", () -> batch.readRetainedInput(0));
        code("WINDOW_INPUT_BYTE_BUDGET_INVALID", () ->
                batch.readRetainedInput(RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES + 1));
        assertTrue(paths.isEmpty());
        assertEquals(retained.inputDigest(), batch.readRetainedInput(RuleCompilationSnapshot.MAX_RETAINED_WINDOW_BYTES).inputDigest());
        journalSubject = "changed";
        var changed = batch.readRetainedInput(100_000);
        assertEquals(retained.cutDigest(), changed.cutDigest());
        assertNotEquals(retained.inputDigest(), changed.inputDigest());
        repeatedJournalEventId = UUID.randomUUID();
        code("WINDOW_INPUT_EVENT_DUPLICATE", () -> batch.readRetainedInput(100_000));
    }

    @Test void materializedPrefixIsDiscardedWhenGrantIsRevokedOnALaterPage() {
        journalResponse();
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, windowRule(), 5);
        var batch = captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        afterJournalRead = () -> {
            if (reads.incrementAndGet() == 2) grant.put("status", "REVOKED").put("epoch", "2");
        };
        code("AUTOMATION_GRANT_INACTIVE", () -> batch.readRetainedInput(100_000));
        assertEquals(2, reads.get());
    }

    @Test void windowPreviewReducesExactSubjectAndWindowWithoutFinalizing() {
        journalResponse();
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, windowRule(), 5);
        var batch = captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15);
        var start = Instant.parse("2026-11-05T00:00:00Z");
        var window = new VclWindowPlan.Window(start, start.plusSeconds(5));
        var preview = batch.preview("subject", window, 100_000);
        assertEquals("4", preview.localValue().literal().path("value").textValue());
        assertEquals("UNKNOWN", preview.upstreamCoverage());
        assertEquals("UNQUALIFIED", preview.eventTimeProvenance());
        assertFalse(preview.finalized());
        assertEquals("0", batch.preview("other", window, 100_000).localValue().literal().path("value").textValue());
        unknownSubjectCheckpoint = "5";
        assertEquals("4", batch.preview("subject", window, 100_000).localValue().literal().path("value").textValue());
        unknownSubjectCheckpoint = "4";
        var unknown = batch.preview("subject", window, 100_000).localValue();
        assertEquals(ScalarValue.Status.UNKNOWN, unknown.status());
        assertEquals("WINDOW_SUBJECT_UNKNOWN", unknown.reason());
        assertEquals(1, unknown.evidence().size());
        unknownSubjectCheckpoint = null;
        journalRecordedAt = "2026-11-05T00:00:02Z";
        var future = batch.preview("subject", window, 100_000).localValue();
        assertEquals(ScalarValue.Status.UNKNOWN, future.status());
        assertEquals("WINDOW_FUTURE_OBSERVATION", future.reason());
        code("AGGREGATE_WINDOW_CHANGED", () -> batch.preview("subject", new VclWindowPlan.Window(start, start.plusSeconds(4)), 100_000));
    }

    @Test void preparedJournalWindowStagesAndConsumesADurableTimerAcrossReconstruction() {
        var journal = journalResponse();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        var migrations = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator();
        for (String migration : List.of("V034__vcl_compilation_bundles", "V035__vcl_keyed_state", "V036__vcl_keyed_timers",
                "V037__vcl_keyed_outbox", "V038__vcl_keyed_rekey_receipts", "V039__vcl_keyed_input_receipt_digests",
                "V040__vcl_rule_deployments", "V041__vcl_deployment_actor", "V042__vcl_legacy_deployment_fence",
                "V043__vcl_deployment_cutover", "V044__vcl_cutover_checkpoint_recipe", "V045__vcl_evaluator_readiness_ack",
                "V046__vcl_journal_window_admission", "V047__vcl_journal_watermarks", "V048__vcl_initial_rule_activation",
                "V049__vcl_pending_alert_delivery", "V050__vcl_journal_window_cursor", "V051__vcl_rule_pause",
                "V052__vcl_session_timeout_cut", "V053__vcl_reviewed_window_corrections")) {
            migrations.addScript(new org.springframework.core.io.ClassPathResource("db/migration/" + migration + ".sql"));
        }
        migrations.execute(source);
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, windowRule(), 5);
        var batch = captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15);
        var start = Instant.parse("2026-11-05T00:00:00Z");
        var window = new VclWindowPlan.Window(start, start.plusSeconds(5));
        UUID epoch = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        var store = new JdbcKeyedStateStore(source);
        var staged = batch.durable(store, epoch, "subject", window, null);
        assertEquals(1, staged.stage(holder, 100_000).committedRevision());
        assertEquals(1, store.dueTimers(ACCOUNT, 10).size());
        journal.put("headCheckpoint", "7");
        var rebuilt = snapshots.restoreWindow(AUTH, ACTOR, ACCOUNT, new JdbcKeyedStateStore(source),
                staged.key(), staged.pinsDigest(), COMPILER);
        assertTrue(rebuilt.stage(holder, 100_000).replay());
        var machineReads = org.mockito.Mockito.mock(WorkerSourceCaptureReads.class);
        org.mockito.Mockito.when(machineReads.capture(ACCOUNT, epoch, BINDING, 1)).thenAnswer(ignored ->
                com.voltcanary.vcl.VclSourceCaptureDescription.parse(responses.get(
                        "/data/source-bindings/" + BINDING + "/capture?schemaRevision=1").toString().getBytes(StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(machineReads.observations(org.mockito.ArgumentMatchers.eq(ACCOUNT),
                org.mockito.ArgumentMatchers.eq(epoch), org.mockito.ArgumentMatchers.eq(BINDING),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> observationPage(call.getArgument(4)));
        var machineGrant = org.mockito.Mockito.mock(WorkerGrantReads.class);
        org.mockito.Mockito.when(machineGrant.read(ACCOUNT, epoch)).thenAnswer(ignored ->
                com.voltcanary.vcl.VclAutomationGrantDescription.parse(grant.toString().getBytes(StandardCharsets.UTF_8)));
        var machinePolicy = org.mockito.Mockito.mock(WorkerPolicyReads.class);
        org.mockito.Mockito.when(machinePolicy.read(ACCOUNT, epoch, POLICY)).thenAnswer(ignored ->
                com.voltcanary.vcl.VclEvidencePolicyDescription.parse(policy.toString().getBytes(StandardCharsets.UTF_8)));
        var machineBundles = org.mockito.Mockito.mock(JdbcCompilationBundleStore.class);
        var machineRegistry = org.mockito.Mockito.mock(JdbcRuleDeployments.class);
        UUID bundleId = UUID.randomUUID();
        String bundleDigest = "a".repeat(64);
        var deployment = new JdbcRuleDeployments.Deployment(ACTOR, ACCOUNT, RULE, epoch, bundleId,
                bundleDigest, GRANT, JdbcRuleDeployments.State.ACTIVE, 1);
        org.mockito.Mockito.when(machineRegistry.find(ACCOUNT, epoch)).thenReturn(java.util.Optional.of(deployment));
        var sealedBundle = org.mockito.Mockito.mock(com.voltcanary.analytics.vcl.CompilationBundle.class);
        org.mockito.Mockito.when(sealedBundle.digest()).thenReturn(bundleDigest);
        var bundlePayload = JSON.createObjectNode();
        bundlePayload.set("compilation", JSON.valueToTree(captured.compile(COMPILER)));
        org.mockito.Mockito.when(sealedBundle.json()).thenReturn(bundlePayload);
        org.mockito.Mockito.when(machineBundles.find(ACCOUNT, bundleId)).thenReturn(java.util.Optional.of(sealedBundle));
        var machine = new WorkerRuleCompilationSnapshots(machineBundles, machineRegistry,
                new WorkerSourceCaptureSnapshots(machineReads), machineGrant, machinePolicy);
        var machineRestored = machine.restoreWindow(deployment, store, staged.key(), staged.pinsDigest(), COMPILER);
        assertEquals(staged.key(), machineRestored.key());
        assertTrue(machineRestored.stage(holder, 100_000).replay());
        code("OWNER_AUTHENTICATION_REQUIRED", () -> snapshots.consumeDueWindows("", ACTOR, ACCOUNT,
                store, COMPILER, holder, 10));
        assertEquals(0, snapshots.consumeDueWindows(AUTH, ACTOR, UUID.randomUUID(), store, COMPILER, holder, 10).size());
        var result = snapshots.consumeDueWindows(AUTH, ACTOR, ACCOUNT, store, COMPILER, holder, 10).getFirst();
        assertEquals(2, result.stateRevision());
        assertEquals(com.voltcanary.analytics.vcl.KeyedStateStore.OutputKind.MEASURE, result.output().kind());
        var payload = PARSER.readJson(result.output().payload().payload().getBytes(StandardCharsets.UTF_8));
        assertEquals("4", payload.at("/literal/value").textValue());
        assertEquals("compare", payload.at("/decision/nodeId").textValue());
        assertEquals("alert", payload.at("/decision/terminalId").textValue());
        assertEquals("OK", payload.at("/decision/status").textValue());
        assertTrue(payload.at("/decision/literal/value").booleanValue());
        assertEquals("UNKNOWN", payload.path("coverage").textValue());
        assertFalse(payload.path("finalized").booleanValue());
        assertFalse(payload.has("recipe"));
        assertTrue(store.dueTimers(ACCOUNT, 10).isEmpty());
        assertTrue(snapshots.consumeDueWindows(AUTH, ACTOR, ACCOUNT, store, COMPILER, holder, 10).isEmpty());
        assertEquals(result, rebuilt.consumeDue(holder));
        var completed = snapshots.restoreWindow(AUTH, ACTOR, ACCOUNT, store, staged.key(), staged.pinsDigest(), COMPILER);
        assertEquals(result, completed.consumeDue(holder));
        journal.put("headCheckpoint", "4");
        code("CAPTURE_CUT_CHANGED", () -> snapshots.restoreWindow(AUTH, ACTOR, ACCOUNT, store,
                staged.key(), staged.pinsDigest(), COMPILER));
        journal.put("headCheckpoint", "7");
        var unmatched = batch.durable(store, epoch, "other", window, null);
        unmatched.stage(holder, 100_000);
        new org.springframework.jdbc.core.JdbcTemplate(source).update(
                "UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?",
                java.time.OffsetDateTime.parse("1970-01-01T00:00:00Z"), ACCOUNT, unmatched.key().digest());
        machine.restoreWindow(deployment, store, unmatched.key(), unmatched.pinsDigest(), COMPILER);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.update("INSERT INTO vcl_compilation_bundles(account_id,bundle_id,bundle_version,payload_digest,canonical_payload) VALUES(?,?,?,?,?)",
                ACCOUNT, bundleId, "test", bundleDigest, bundlePayload.toString());
        jdbc.update("""
                INSERT INTO vcl_rule_deployments(account_id,rule_id,deployment_epoch,bundle_id,bundle_digest,grant_id,
                    actor_id,state,active_slot,revision) VALUES(?,?,?,?,?,?,?,'ACTIVE',1,1)
                """, ACCOUNT, RULE, epoch, bundleId, bundleDigest, GRANT, ACTOR);
        var workerAdmission = org.mockito.Mockito.mock(WorkerDeploymentAdmission.class);
        org.mockito.Mockito.when(workerAdmission.candidates(10, 0)).thenReturn(List.of(deployment));
        org.mockito.Mockito.when(workerAdmission.inspect(deployment)).thenReturn(
                new WorkerDeploymentAdmission.Inspection(deployment, Map.of(), Map.of(), false));
        var worker = new WorkerDueWindows(workerAdmission, machine, store, COMPILER);
        var workerOutputs = worker.drain(10, 10);
        assertEquals(1, workerOutputs.size());
        assertTrue(worker.drain(10, 10).isEmpty());
        var unmatchedDecision = PARSER.readJson(workerOutputs.getFirst().output().payload().payload()
                .getBytes(StandardCharsets.UTF_8)).path("decision");
        assertEquals("OK", unmatchedDecision.path("status").textValue());
        assertFalse(unmatchedDecision.at("/literal/value").booleanValue());
        UUID missingProof = UUID.randomUUID();
        org.mockito.Mockito.when(machineReads.coverage(org.mockito.ArgumentMatchers.eq(ACCOUNT),
                org.mockito.ArgumentMatchers.eq(epoch), org.mockito.ArgumentMatchers.eq(BINDING),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(missingProof),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.voltcanary.vcl.VclSourceCoverage(
                        missingProof, null, UUID.fromString(this.source.path("snapshotId").asText()),
                        "UNKNOWN", "PROOF_UNAVAILABLE", null, null));
        var unproven = batch.durable(store, epoch, "unproven", window, missingProof);
        unproven.stage(holder, 100_000);
        new org.springframework.jdbc.core.JdbcTemplate(source).update(
                "UPDATE vcl_keyed_state SET lease_expires_at=? WHERE account_id=? AND key_digest=?",
                java.time.OffsetDateTime.parse("1970-01-01T00:00:00Z"), ACCOUNT, unproven.key().digest());
        var unprovenOutputs = worker.drain(10, 10);
        assertEquals(1, unprovenOutputs.size());
        var unprovenResult = PARSER.readJson(unprovenOutputs.getFirst().output().payload().payload()
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("UNKNOWN", unprovenResult.path("coverage").textValue());
        assertFalse(unprovenResult.path("finalized").booleanValue());
        unknownSubjectCheckpoint = "4";
        var ambiguous = batch.durable(store, UUID.randomUUID(), "subject", window, null);
        ambiguous.stage(holder, 100_000);
        var unknownDecision = PARSER.readJson(ambiguous.consumeDue(holder).output().payload().payload()
                .getBytes(StandardCharsets.UTF_8)).path("decision");
        assertEquals("UNKNOWN", unknownDecision.path("status").textValue());
        assertTrue(unknownDecision.path("literal").isNull());
        var blocked = batch.durable(store, epoch, "blocked", window, null);
        blocked.stage(holder, 100_000);
        grant.put("status", "REVOKED").put("epoch", "2");
        assertTrue(new WorkerDueWindows(workerAdmission, machine, store, COMPILER).drain(10, 10).isEmpty());
        assertEquals(1, store.dueTimers(ACCOUNT, RULE, epoch, 10).size());
        var revokedDeployment = new JdbcRuleDeployments.Deployment(ACTOR, ACCOUNT, RULE, epoch, bundleId,
                bundleDigest, GRANT, JdbcRuleDeployments.State.REVOKED, 2);
        org.mockito.Mockito.when(machineRegistry.find(ACCOUNT, epoch)).thenReturn(java.util.Optional.of(revokedDeployment));
        assertTrue(worker.drain(10, 10).isEmpty());
        assertEquals(1, store.dueTimers(ACCOUNT, RULE, epoch, 10).size());
        code("AUTOMATION_GRANT_INACTIVE", () -> snapshots.consumeDueWindows(AUTH, ACTOR, ACCOUNT,
                store, COMPILER, holder, 10));
        assertEquals(1, store.dueTimers(ACCOUNT, 10).size());
        code("AUTOMATION_GRANT_INACTIVE", () -> rebuilt.consumeDue(holder));
    }

    @Test void windowPreviewUsesTheCompiledTextReducer() {
        journalResponse();
        var json = windowRule().json();
        ((ObjectNode) json.at("/spec/nodes/2/config")).put("reducer", "MIN").put("valueFieldId", SUBJECT.toString());
        ((ObjectNode) json.at("/spec/nodes/3/config/predicate/args/1/literal")).put("type", "TEXT").put("value", "");
        var captured = snapshots.captureJournal(AUTH, ACTOR, ACCOUNT, PARSER.parse(json.toString()), 5);
        var batch = captured.prepareJournalWindowBatch(COMPILER, "aggregate", 15);
        var start = Instant.parse("2026-11-05T00:00:00Z");
        var value = batch.preview("subject", new VclWindowPlan.Window(start, start.plusSeconds(5)), 100_000).localValue();
        assertEquals("TEXT", value.literal().path("type").textValue());
        assertEquals("subject", value.literal().path("value").textValue());
    }

    private VclObservationPage observationPage(VclObservationRead request) {
        long after = Long.parseLong(request.afterCheckpoint());
        long through = Long.parseLong(request.throughCheckpoint());
        if (request.eventFrom() != null) {
            var from = Instant.parse(request.eventFrom());
            var until = Instant.parse(request.eventUntil());
            int limit = Math.min(journalPageSize, request.limit());
            var matches = new ArrayList<JsonNode>();
            for (long index = after + 1; index <= through; index++) {
                var row = observationRow(request, Long.toString(index));
                var at = Instant.parse(row.path("eventTime").textValue());
                if (at.isBefore(from) || !at.isBefore(until)) continue;
                if (matches.size() == limit) {
                    return VclObservationPage.ranged(request, matches, true, limit == request.limit() ? "PAGE_ROWS" : "PAGE_BYTES");
                }
                matches.add(row);
            }
            return VclObservationPage.ranged(request, matches, false, null);
        }
        int count = (int) Math.min(through - after, Math.min(journalPageSize, request.limit()));
        var rows = new ArrayList<JsonNode>(count);
        for (int index = 1; index <= count; index++) rows.add(observationRow(request, Long.toString(after + index)));
        return VclObservationPage.of(request, rows, count == request.limit() ? "PAGE_ROWS" : "PAGE_BYTES");
    }

    private ObjectNode observationRow(VclObservationRead request, String checkpoint) {
        UUID eventId = repeatedJournalEventId == null
                ? UUID.nameUUIDFromBytes(checkpoint.getBytes(StandardCharsets.UTF_8)) : repeatedJournalEventId;
        var row = JSON.createObjectNode().put("profile", VclObservationProfile.VERSION)
                .put("inputEventId", eventId.toString())
                .put("inputGeneration", request.inputGeneration().toString())
                .put("checkpoint", checkpoint)
                .put("eventTime", Instant.parse("2026-11-05T00:00:00Z").plusSeconds(Long.parseLong(checkpoint)).toString())
                .put("receivedAt", "2026-11-05T00:00:06Z")
                .put("recordedAt", journalRecordedAt).put("upstreamCoverage", "UNKNOWN")
                .put("eventTimeProvenance", "UNQUALIFIED").put("negativeEvidenceReady", false).put("ruleExecutionReady", false);
        row.set("bindingSchemaPin", JSON.valueToTree(request.pin()));
        var fields = row.putArray("fields");
        var subject = fields.addObject().put("fieldId", SUBJECT.toString()).put("type", "TEXT");
        if (checkpoint.equals(unknownSubjectCheckpoint)) {
            subject.put("knowledge", "UNKNOWN").put("reason", "MISSING_OR_NULL_INPUT").putNull("value");
        } else {
            subject.put("knowledge", "KNOWN").putNull("reason").put("value", journalSubject);
        }
        fields.addObject().put("fieldId", FLAG.toString()).put("type", "BOOLEAN").put("knowledge", "KNOWN").putNull("reason").put("value", true);
        augmentObservation.accept(row);
        return row;
    }

    private static void unknownField(com.fasterxml.jackson.databind.node.ArrayNode fields, String id, String type) {
        fields.addObject().put("fieldId", id).put("type", type).put("knowledge", "UNKNOWN")
                .put("reason", "MISSING_OR_NULL_INPUT").putNull("value");
    }

    private static void instantField(com.fasterxml.jackson.databind.node.ArrayNode fields, String id, Instant value) {
        fields.addObject().put("fieldId", id).put("type", "INSTANT").put("knowledge", "KNOWN")
                .putNull("reason").put("value", value.toString());
    }

    private static void assertCompilationRejected(org.springframework.test.web.servlet.MockMvc http,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post, JsonNode request, String code)
            throws Exception {
        var response = http.perform(post.content(request.toString())).andReturn().getResponse();
        assertEquals(409, response.getStatus());
        assertEquals(code, PARSER.readJson(response.getContentAsByteArray()).path("code").textValue());
    }

    private static JsonNode rulePreview(org.springframework.test.web.servlet.MockMvc http, String endpoint, JsonNode receipt,
            String node, Instant from, Instant until, UUID circle, String occurrence) throws Exception {
        var body = JSON.createObjectNode().put("account", ACCOUNT.toString()).put("bundle", receipt.path("bundleId").textValue())
                .put("node", node).put("from", from.toString()).put("until", until.toString())
                .put("subjectLiteral", "{\"type\":\"TEXT\",\"value\":\"subject\"}");
        body.set("checkpoint", receipt.path("checkpoint"));
        if (circle != null) {
            body.put("circle", circle.toString()).put("occurrenceLiteral",
                    JSON.createObjectNode().put("type", "TEXT").put("value", occurrence).toString());
        }
        var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/analytics/configurable/rule-cutovers/" + endpoint).header("Authorization", AUTH)
                .header("X-User-Id", ACTOR).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body.toString())).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return PARSER.readJson(response.getContentAsByteArray());
    }

    private ObjectNode journalResponse() {
        var response = JSON.createObjectNode();
        response.set("source", source);
        var journal = response.putObject("capture").put("profile", VclObservationProfile.VERSION).put("state", "READY")
                .put("inputGeneration", UUID.randomUUID().toString()).put("generationStartCheckpoint", "0").put("headCheckpoint", "5")
                .put("generationStartedAt", "2026-11-05T00:00:00Z").put("journalReady", true)
                .put("negativeEvidenceReady", false).put("ruleExecutionReady", false).put("transitionCoverage", "UNKNOWN")
                .put("deletionCoverage", "UNKNOWN").put("eventTimeProvenance", "UNQUALIFIED").putNull("maximumGapUs");
        journal.putObject("pin").put("bindingId", BINDING.toString()).put("accountId", ACCOUNT.toString())
                .put("deviceId", source.path("deviceId").asText()).put("externalSourceId", "source").put("schemaRevision", 1)
                .put("snapshotId", source.path("snapshotId").asText()).put("definitionDigest", source.path("definitionDigest").asText());
        responses.put("/data/source-bindings/" + BINDING + "/capture?schemaRevision=1", response);
        return journal;
    }

    @Test void exactFieldMembershipIsRequiredForEveryExposedSourceField() {
        ((com.fasterxml.jackson.databind.node.ArrayNode) grant.at("/definition/sourceBindings/0/fieldIds")).remove(1);
        grant.put("definitionDigest", hash(grant.path("definition")));
        policy.put("grantDefinitionDigest", grant.path("definitionDigest").asText());
        var captured = capture(document());
        code("AUTOMATION_GRANT_SCOPE_DENIED", () -> captured.compile(COMPILER));
    }

    @Test void sourceScopeHeadAndAvailabilityAreRecheckedAfterCapture() {
        var captured = capture(document());
        source.put("accessScopeDigest", "c".repeat(64));
        code("ACCESS_SCOPE_CHANGED", () -> captured.compile(COMPILER));
        source.put("accessScopeDigest", "a".repeat(64)).put("sourceStateDigest", "c".repeat(64));
        code("METADATA_SNAPSHOT_CHANGED", () -> captured.compile(COMPILER));
        source.put("sourceStateDigest", "b".repeat(64)).put("sourceActive", false);
        code("SOURCE_NOT_ACTIVE", () -> captured.compile(COMPILER));
    }

    @Test void evidencePolicyDriftOrGrantMismatchCannotBeHiddenByStaticSchema() {
        var captured = capture(document());
        ((ObjectNode) policy.path("definition")).put("retentionDays", 8);
        policy.put("definitionDigest", hash(policy.path("definition")));
        code("EVIDENCE_POLICY_CHANGED", () -> captured.compile(COMPILER));
        policy.put("grantEpoch", "2");
        code("EVIDENCE_POLICY_GRANT_CHANGED", () -> captured.compile(COMPILER));
    }

    @Test void accountActorAndRuleMustMatchActualOwnerIdentities() {
        for (String field : List.of("accountId", "actorId", "ruleId")) {
            var original = grant.path(field).asText();
            grant.put(field, UUID.randomUUID().toString());
            code("OWNER_SNAPSHOT_INVALID", () -> capture(document()));
            grant.put(field, original);
        }
        source.put("accountId", UUID.randomUUID().toString());
        code("OWNER_SNAPSHOT_INVALID", () -> capture(document()));
    }

    @Test void foreignPolicyCannotAcquirePermissionFromAGrant() {
        var altered = document().json();
        ((ObjectNode) altered.at("/spec/nodes/2/config")).put("evidencePolicyId", UUID.randomUUID().toString());
        code("EVIDENCE_POLICY_UNAVAILABLE", () -> capture(PARSER.parse(altered.toString())));
    }

    @Test void unknownDocumentFieldsAndMethodsAreRejectedBeforeOwnerIo() {
        var altered = document().json();
        ((ObjectNode) altered.path("spec")).put("executionReady", true);
        assertThrows(VclException.class, () -> capture(PARSER.parse(altered.toString())));
        var unknown = document().json();
        ((ObjectNode) unknown.at("/spec/nodes/1")).put("method", "execute_sql");
        assertThrows(VclException.class, () -> capture(PARSER.parse(unknown.toString())));
        assertTrue(paths.isEmpty());
    }

    @Test void ownerFailureAfterCaptureNeverFallsBackToPinnedAuthority() {
        var captured = capture(document());
        status = 403;
        code("OWNER_ACCESS_REVOKED", () -> captured.compile(COMPILER));
        status = 503;
        code("SOURCE_BINDING_OWNER_UNAVAILABLE", () -> captured.compile(COMPILER));
    }

    @Test void nativeLookupUsesOwnerSchemaAndCannotInventAsOfReadiness() {
        UUID datasetId = UUID.randomUUID();
        var dataset = nativeDataset(datasetId);
        responses.put("/data/datasets/" + datasetId + "?schemaRevision=1", dataset);
        allowDataset(datasetId);
        var captured = capture(withLookup(datasetId));
        code("PROJECTION_UNAVAILABLE", () -> captured.compile(COMPILER));
        assertTrue(paths.contains("/data/datasets/" + datasetId + "?schemaRevision=1"));
    }

    @Test void preparedLookupUsesVerifiedManifestAndFreshGrant() {
        UUID datasetId = UUID.randomUUID();
        UUID interval = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        var dataset = nativeDataset(datasetId);
        var definition = (ObjectNode) dataset.path("definition");
        ((com.fasterxml.jackson.databind.node.ArrayNode) definition.path("fields")).addObject()
                .put("id", interval.toString()).put("label", "Effective").put("type", "INTERVAL").put("nullable", false);
        var collection = JSON.createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
        var schema = definition.deepCopy();
        schema.remove(List.of("generation", "dataVersion", "systemFields"));
        collection.set("spec", schema);
        dataset.put("schemaDigest", VclCanonical.documentDigest(PARSER.parse(collection.toString())));
        String path = "/data/datasets/" + datasetId;
        responses.put(path + "?schemaRevision=1", dataset);
        allowDataset(datasetId);
        ((com.fasterxml.jackson.databind.node.ArrayNode) grant.at("/definition/datasets/0/fieldIds")).add(interval.toString());
        grant.put("definitionDigest", hash(grant.path("definition")));
        policy.put("grantDefinitionDigest", grant.path("definitionDigest").asText());
        var query = PARSER.parse("""
                {"language":"VCL/1","kind":"QUERY","spec":{
                  "target":{"datasetId":"%s","schemaRevision":1},"select":["%s","%s","%s"],
                  "where":{"op":"CONST","literal":{"type":"BOOLEAN","value":true}},
                  "order":[{"fieldId":"%s","direction":"ASC"}],"limit":10,"cursor":null,
                  "read":{"mode":"SNAPSHOT","snapshotId":"%s"}}}
                """.formatted(datasetId, SUBJECT, FLAG, interval, definition.at("/systemFields/recordIdFieldId").textValue(), snapshot));
        var manifest = JSON.createObjectNode().put("datasetId", datasetId.toString()).put("schemaRevision", 1)
                .put("snapshotId", snapshot.toString()).put("consistency", "SNAPSHOT")
                .put("generation", dataset.path("generation").textValue()).put("dataVersion", 1)
                .put("schemaDigest", dataset.path("schemaDigest").textValue())
                .put("accessScopeDigest", dataset.path("accessScopeDigest").textValue()).put("queryDigest", "b".repeat(64));
        responses.put(path + "/query-asof-manifest", manifest);
        var outcome = JSON.createObjectNode().put("status", "UNKNOWN").put("reason", "ASOF_MISSING").putNull("row");
        outcome.set("readManifest", manifest.deepCopy());
        responses.put(path + "/query-asof", outcome);
        verifyLookupBundle(datasetId, query, interval, manifest);
        var prepared = snapshots.prepareLookups(AUTH, ACTOR, ACCOUNT, withLookup(datasetId), COMPILER,
                Map.of("reference", new RuleCompilationSnapshot.LookupSelection(query, interval)));
        assertTrue(prepared.compilation().graph().stream().anyMatch(node -> node.method().equals("lookup_asof")));
        Map<UUID, JsonNode> event = Map.of(SUBJECT, JSON.createObjectNode().put("type", "TEXT").put("value", "asset"));
        var time = java.time.Instant.parse("2026-11-05T00:00:00Z");
        assertEquals(outcome, prepared.lookup("reference", event, time));
        dataset.put("dataVersion", 2);
        definition.put("dataVersion", 2);
        assertEquals(outcome, prepared.lookup("reference", event, time));
        var replay = snapshots.prepareLookups(AUTH, ACTOR, ACCOUNT, withLookup(datasetId), COMPILER,
                Map.of("reference", new RuleCompilationSnapshot.LookupSelection(query, interval)));
        assertEquals(prepared.compilation(), replay.compilation());
        ((ObjectNode) outcome.path("readManifest")).put("dataVersion", 2);
        code("METADATA_SNAPSHOT_CHANGED", () -> prepared.lookup("reference", event, time));
        grant.put("status", "REVOKED").put("epoch", "2");
        paths.clear();
        code("AUTOMATION_GRANT_INACTIVE", () -> prepared.lookup("reference", event, time));
        assertFalse(paths.contains(path + "/query-asof"));
    }

    private void verifyLookupBundle(UUID datasetId, VclDocument query, UUID interval, ObjectNode manifest) {
        var journal = journalResponse();
        UUID proof = UUID.randomUUID();
        var from = "2026-11-05T00:00:00Z";
        var until = "2026-11-05T00:00:05Z";
        var coverage = JSON.createObjectNode().put("proofId", proof.toString())
                .put("inputGeneration", journal.path("inputGeneration").textValue())
                .put("snapshotId", journal.at("/pin/snapshotId").textValue())
                .put("coverage", "COVERED").putNull("reason").put("requiredCheckpoint", "5").put("acceptedAt", until);
        coverage.putObject("evidence").put("proofId", proof.toString())
                .put("inputGeneration", journal.path("inputGeneration").textValue())
                .put("from", from).put("until", until).put("eventTimeBasis", "SOURCE_EVENT_TIME_V1")
                .putObject("position").put("epoch", UUID.randomUUID().toString()).put("sequence", "5");
        responses.put("/data/source-bindings/" + BINDING + "/coverage/" + proof, coverage);
        var document = windowRule().json();
        ((ObjectNode) document.at("/spec/nodes/2/inputs")).put("events", "reference");
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.at("/spec/nodes"))
                .add(withLookup(datasetId).json().at("/spec/nodes/3"));
        var request = JSON.createObjectNode();
        request.set("document", document);
        request.putObject("readSet").put("account", ACCOUNT.toString()).put("binding", BINDING.toString())
                .put("schemaRevision", 1).put("proofId", proof.toString()).put("from", from).put("boundary", until)
                .putObject("lookups").putObject("reference").put("intervalField", interval.toString()).set("query", query.json());
        var database = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V034__vcl_compilation_bundles.sql")).execute(database);
        var service = new ReviewedRuleCutovers(database, snapshots, JacksonRestClients.builder(), new AnalyticsDownstreamProperties());
        var receipt = service.compile(AUTH, ACTOR, request.toString().getBytes(StandardCharsets.UTF_8));
        var bundle = new JdbcCompilationBundleStore(database).find(ACCOUNT, receipt.bundleId()).orElseThrow();
        assertEquals(receipt.bundleDigest(), bundle.digest());
        assertEquals(hash(manifest), receipt.checkpoint().references().get("reference").manifestDigest());
        receipt.checkpoint().verifyBundleInputs(bundle);
    }

    @Test void nativeScopeOrDataHeadDriftInvalidatesCompositeSnapshot() {
        UUID datasetId = UUID.randomUUID();
        var dataset = nativeDataset(datasetId);
        responses.put("/data/datasets/" + datasetId + "?schemaRevision=1", dataset);
        allowDataset(datasetId);
        dataset.put("accessScopeDigest", "c".repeat(64));
        code("ACCESS_SCOPE_CHANGED", () -> capture(withLookup(datasetId)));
        dataset.put("accessScopeDigest", "a".repeat(64));
        var captured = capture(withLookup(datasetId));
        dataset.put("dataVersion", 2);
        ((ObjectNode) dataset.path("definition")).put("dataVersion", 2);
        code("METADATA_SNAPSHOT_CHANGED", () -> captured.compile(COMPILER));
    }

    @Test void sourcePermissionDoesNotAuthorizeNativeFields() {
        UUID datasetId = UUID.randomUUID();
        responses.put("/data/datasets/" + datasetId + "?schemaRevision=1", nativeDataset(datasetId));
        var captured = capture(withLookup(datasetId));
        code("AUTOMATION_GRANT_SCOPE_DENIED", () -> captured.compile(COMPILER));
    }

    @Test void missingIdentityNonRuleAndContradictoryRevisionsFailBeforeIo() {
        code("OWNER_AUTHENTICATION_REQUIRED", () -> snapshots.capture(null, ACTOR, ACCOUNT, document()));
        code("OWNER_AUTHENTICATION_REQUIRED", () -> snapshots.capture(AUTH, null, ACCOUNT, document()));
        code("RULE_REQUIRED", () -> snapshots.capture(AUTH, ACTOR, ACCOUNT, null));
        var altered = document().json();
        var extra = altered.at("/spec/nodes/0").deepCopy();
        ((ObjectNode) extra).put("id", "other");
        ((ObjectNode) extra.path("config")).put("schemaRevision", 2);
        ((com.fasterxml.jackson.databind.node.ArrayNode) altered.at("/spec/nodes")).add(extra);
        code("METADATA_SNAPSHOT_CHANGED", () -> capture(PARSER.parse(altered.toString())));
        assertTrue(paths.isEmpty());
    }

    private RuleCompilationSnapshot capture(VclDocument document) { return snapshots.capture(AUTH, ACTOR, ACCOUNT, document); }

    private void allowDataset(UUID id) {
        ((com.fasterxml.jackson.databind.node.ArrayNode) grant.at("/definition/datasets")).addObject()
                .put("id", id.toString()).put("schemaRevision", 1).putArray("fieldIds").add(SUBJECT.toString()).add(FLAG.toString());
        grant.put("definitionDigest", hash(grant.path("definition")));
        policy.put("grantDefinitionDigest", grant.path("definitionDigest").asText());
    }

    private ObjectNode nativeDataset(UUID id) {
        var collection = PARSER.parse("""
                {"language":"VCL/1","kind":"COLLECTION","spec":{
                  "id":"%s","revision":1,"label":"Reference table","fields":[
                    {"id":"%s","label":"Subject","type":"TEXT","maxLength":120,"nullable":false},
                    {"id":"%s","label":"Flag","type":"BOOLEAN","nullable":true}],
                  "storage":{"mode":"NATIVE","indexedFieldIds":["%s"],"history":"NONE"},"keyFieldIds":["%s"]}}
                """.formatted(id, SUBJECT, FLAG, SUBJECT, SUBJECT));
        UUID generation = UUID.randomUUID();
        var definition = (ObjectNode) collection.spec();
        definition.put("generation", generation.toString()).put("dataVersion", 1);
        definition.putObject("systemFields").put("recordIdFieldId", UUID.randomUUID().toString());
        var result = JSON.createObjectNode().put("id", id.toString()).put("schemaRevision", 1)
                .put("schemaDigest", VclCanonical.documentDigest(collection)).put("accessScopeDigest", "a".repeat(64))
                .put("generation", generation.toString()).put("dataVersion", 1).put("storageProfile", "native-dense-1")
                .put("liveQueryReady", true).put("historyReady", false);
        result.set("definition", definition);
        result.putArray("readyIndexedFields").add(SUBJECT.toString());
        result.putObject("queryCapabilities").put("profile", "native-live-sql-1");
        return result;
    }

    private VclDocument withLookup(UUID id) {
        var json = document().json();
        ((ObjectNode) json.at("/spec/nodes/1/inputs")).put("events", "reference");
        var lookup = ((com.fasterxml.jackson.databind.node.ArrayNode) json.at("/spec/nodes")).addObject()
                .put("id", "reference").put("method", "lookup_asof");
        lookup.putObject("inputs").put("events", "source");
        var config = lookup.putObject("config");
        config.putObject("target").put("datasetId", id.toString()).put("schemaRevision", 1);
        config.putArray("keyPairs").addObject().put("eventFieldId", SUBJECT.toString()).put("recordFieldId", SUBJECT.toString());
        config.putArray("select").add(FLAG.toString());
        config.put("cardinality", "EXACT_ONE").put("recordedAt", "ACCEPTANCE_CHECKPOINT");
        config.putObject("temporal").put("mode", "EVENT_TIME");
        return PARSER.parse(json.toString());
    }
    private static String hash(JsonNode node) { return VclCanonical.sha256(VclCanonical.encode(node)); }
    private static void code(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(VclException.class, action).code());
    }

    private VclDocument document() {
        return PARSER.parse("""
                {"language":"VCL/1","kind":"RULE","spec":{
                  "ruleId":"%s","revision":1,"name":"Boolean source rule","executionGrantId":"%s",
                  "coverage":{"maxGapUs":"2000000","onUnknown":"UNKNOWN"},
                  "decimalPolicy":{"precision":38,"scale":6,"rounding":"REJECT"},
                  "nodes":[
                    {"id":"source","method":"read_stream","inputs":{},"config":{"bindingId":"%s","schemaRevision":1}},
                    {"id":"condition","method":"sustained","inputs":{"events":"source"},"config":{
                      "subjectFieldId":"%s","predicate":{"op":"FIELD","fieldId":"%s"},
                      "minimumDurationUs":"1000000","maxGapUs":"2000000"}},
                    {"id":"alert","method":"emit_alert","inputs":{"decisions":"condition"},"config":{
                      "severity":"WARNING","dedup":"RULE_SUBJECT_OCCURRENCE","evidencePolicyId":"%s"}}],
                  "terminal":"alert","timing":{"allowedLatenessUs":"1000000","maxFutureSkewUs":"1000000","idleTimeoutUs":"5000000"}}}
                """.formatted(RULE, GRANT, BINDING, SUBJECT, FLAG, POLICY));
    }
}
