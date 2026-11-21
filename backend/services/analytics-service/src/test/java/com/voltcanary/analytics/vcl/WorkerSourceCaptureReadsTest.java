package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.analytics.vcl.adapter.JdbcCompilationBundleStore;
import com.voltcanary.analytics.vcl.adapter.JdbcRuleDeployments;
import com.voltcanary.analytics.vcl.adapter.WorkerSourceCaptureReads;
import com.voltcanary.analytics.vcl.adapter.WorkerGrantReads;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationProfile;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclSourceBindingDescription;
import com.voltcanary.vcl.VclSourceBindingProfile;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import com.voltcanary.vcl.VclObservationRead;
import com.voltcanary.vcl.VclObservationPage;
import com.voltcanary.vcl.VclSourceCoverage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class WorkerSourceCaptureReadsTest {
    @Test void machineReadUsesActiveDeploymentAndRejectsRevocationDuringOwnerResponse() throws Exception {
        var database = new DriverManagerDataSource("jdbc:h2:mem:worker-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V034__vcl_compilation_bundles.sql"),
                new ClassPathResource("db/migration/V040__vcl_rule_deployments.sql"),
                new ClassPathResource("db/migration/V041__vcl_deployment_actor.sql"),
                new ClassPathResource("db/migration/V042__vcl_legacy_deployment_fence.sql"),
                new ClassPathResource("db/migration/V043__vcl_deployment_cutover.sql"),
                new ClassPathResource("db/migration/V051__vcl_rule_pause.sql")).execute(database);
        var account = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var bundle = BundleFixtures.bundle(account, UUID.randomUUID(), "worker-source");
        var bundles = new JdbcCompilationBundleStore(database);
        bundles.put(bundle);
        var registry = new JdbcRuleDeployments(database, bundles);
        var active = registry.activate(account, registry.stage(actor, bundle, UUID.randomUUID()).epoch(), ignored -> { });
        var compiled = new com.voltcanary.vcl.VclParser().readJson(bundle.payload().getBytes(StandardCharsets.UTF_8))
                .path("compilation");
        var stream = java.util.stream.StreamSupport.stream(compiled.path("graph").spliterator(), false)
                .filter(node -> "read_stream".equals(node.path("method").asText())).findFirst().orElseThrow();
        var binding = UUID.fromString(stream.path("config").path("bindingId").asText());
        long revision = stream.path("config").path("schemaRevision").asLong();
        var lookup = java.util.stream.StreamSupport.stream(compiled.path("graph").spliterator(), false)
                .filter(node -> "lookup_asof".equals(node.path("method").asText())).findFirst().orElseThrow();
        var dataset = UUID.fromString(lookup.path("config").path("target").path("datasetId").asText());
        long datasetRevision = lookup.path("config").path("target").path("schemaRevision").asLong();
        var definition = new ObjectMapper().createObjectNode().put("id", binding.toString())
                .put("schemaRevision", revision).put("profile", VclSourceBindingProfile.VERSION)
                .put("configurationDigest", "a".repeat(64));
        definition.putArray("fields").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "BOOLEAN").put("label", "Switch").put("nullable", true);
        var digest = VclCanonical.sha256(VclCanonical.encode(definition));
        var device = UUID.randomUUID();
        var snapshot = UUID.randomUUID();
        var source = new VclSourceBindingDescription(binding, account, actor, device, revision, revision,
                snapshot, definition, digest, "a".repeat(64), "a".repeat(64), true, false,
                VclSourceBindingProfile.capabilities());
        var pin = new VclObservationReadiness.Pin(binding, account, device, "source", revision, snapshot, digest);
        var readiness = new VclObservationReadiness(pin, VclObservationProfile.VERSION, "READY", bundle.inputGeneration(binding),
                "0", "1", Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                true, false, false, "UNKNOWN", "UNKNOWN", "UNQUALIFIED", null);
        var body = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsBytes(new VclSourceCaptureDescription(source, readiness));
        var nativeDocument = new ObjectMapper().createObjectNode().put("language", "VCL/1").put("kind", "COLLECTION");
        var nativeSpec = nativeDocument.putObject("spec").put("id", dataset.toString())
                .put("revision", datasetRevision).put("label", "Native");
        nativeSpec.putArray("fields").addObject().put("id", UUID.randomUUID().toString())
                .put("label", "Name").put("type", "TEXT").put("maxLength", 120).put("nullable", true);
        nativeSpec.putArray("keyFieldIds");
        nativeSpec.putObject("storage").put("mode", "NATIVE").put("history", "NONE").putArray("indexedFieldIds");
        var nativeParsed = new com.voltcanary.vcl.VclParser().parse(nativeDocument.toString());
        var nativeDefinition = (com.fasterxml.jackson.databind.node.ObjectNode) nativeParsed.spec();
        var nativeGeneration = UUID.randomUUID();
        nativeDefinition.put("generation", nativeGeneration.toString()).put("dataVersion", 1);
        nativeDefinition.putObject("systemFields").put("recordIdFieldId", UUID.randomUUID().toString());
        var nativeResponse = new ObjectMapper().createObjectNode().put("id", dataset.toString())
                .put("schemaRevision", datasetRevision).put("schemaDigest", VclCanonical.documentDigest(nativeParsed))
                .put("accessScopeDigest", "a".repeat(64)).put("generation", nativeGeneration.toString())
                .put("dataVersion", 1).put("storageProfile", "native-dense-1")
                .put("liveQueryReady", true).put("historyReady", false);
        nativeResponse.set("definition", nativeDefinition);
        nativeResponse.putArray("readyIndexedFields");
        nativeResponse.putObject("queryCapabilities").put("profile", "native-live-sql-1");
        byte[] datasetBody = nativeResponse.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(dataset, com.voltcanary.vcl.VclDatasetDescriptions.parse(datasetBody).id());
        var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        var verifier = new VclWorkerAssertion(key, "worker-1", "device-service", java.time.Clock.systemUTC());
        var revokeDuringRead = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var captureBody = new java.util.concurrent.atomic.AtomicReference<>(body);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().toString();
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            var assertion = exchange.getRequestHeaders().getFirst("X-VCL-Worker-Assertion");
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String operation = path.startsWith("/internal/vcl/datasets/") ? "DATASET_DESCRIPTION"
                    : path.endsWith("/observations") ? "SOURCE_OBSERVATIONS"
                    : path.contains("/coverage/") ? "SOURCE_COVERAGE" : "SOURCE_CAPTURE";
            var scope = verifier.verify(assertion, operation,
                    operation.equals("DATASET_DESCRIPTION") ? dataset : binding,
                    VclWorkerAssertion.requestDigest(exchange.getRequestMethod(), path, requestBody));
            assertEquals(active.actor(), scope.actor());
            assertEquals(active.epoch(), scope.epoch());
            assertEquals(active.grant(), scope.grant());
            if (revokeDuringRead.get() && operation.equals("SOURCE_OBSERVATIONS")) registry.revoke(account, active.epoch());
            byte[] response = operation.equals("DATASET_DESCRIPTION") ? datasetBody : captureBody.get();
            if (operation.equals("SOURCE_OBSERVATIONS")) {
                var request = VclObservationRead.parse(requestBody);
                var row = new ObjectMapper().createObjectNode().put("profile", VclObservationProfile.VERSION)
                        .put("inputEventId", UUID.randomUUID().toString())
                        .put("inputGeneration", request.inputGeneration().toString()).put("checkpoint", "1")
                        .put("eventTime", "2026-11-05T00:00:00Z").put("receivedAt", "2026-11-05T00:00:01Z")
                        .put("recordedAt", "2026-11-05T00:00:02Z").put("upstreamCoverage", "UNKNOWN")
                        .put("eventTimeProvenance", "UNQUALIFIED").put("negativeEvidenceReady", false)
                        .put("ruleExecutionReady", false);
                row.set("bindingSchemaPin", new ObjectMapper().valueToTree(pin));
                row.putArray("fields").addObject().put("fieldId", definition.path("fields").get(0).path("id").asText())
                        .put("type", "BOOLEAN").put("knowledge", "KNOWN").putNull("reason").put("value", true);
                response = VclObservationPage.of(request, java.util.List.of(row), null).bytes();
            } else if (operation.equals("SOURCE_COVERAGE")) {
                var proof = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
                response = new ObjectMapper().writeValueAsBytes(new VclSourceCoverage(proof, null, snapshot,
                        "UNKNOWN", "PROOF_UNAVAILABLE", null, null));
            }
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var properties = new AnalyticsDownstreamProperties();
            properties.getDeviceService().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getDeviceService().setTimeout(Duration.ofSeconds(2));
            var grants = org.mockito.Mockito.mock(WorkerGrantReads.class);
            var worker = new WorkerSourceCaptureReads(database, JacksonRestClients.builder(), properties,
                    new VclWorkerIdentityProperties(Base64.getEncoder().encodeToString(key), "worker-1"), grants);
            assertEquals(binding, worker.capture(account, active.epoch(), binding, revision).source().id());
            assertEquals(1, calls.get());
            org.mockito.Mockito.doThrow(new VclException("AUTOMATION_GRANT_INACTIVE", ""))
                    .when(grants).read(account, active.epoch());
            assertEquals("AUTOMATION_GRANT_INACTIVE", assertThrows(VclException.class,
                    () -> worker.capture(account, active.epoch(), binding, revision)).code());
            assertEquals(1, calls.get());
            org.mockito.Mockito.reset(grants);
            var request = new VclObservationRead(pin, readiness.inputGeneration(), "0", "1", 1);
            assertEquals(1, worker.observations(account, active.epoch(), binding, revision, request).observations().size());
            var proof = UUID.randomUUID();
            assertEquals("UNKNOWN", worker.coverage(account, active.epoch(), binding, revision, proof, pin).coverage());
            assertEquals("WORKER_DATASET_NOT_PINNED", assertThrows(VclException.class,
                    () -> worker.datasetDescription(account, active.epoch(), UUID.randomUUID(), datasetRevision)).code());
            assertEquals("OWNER_SNAPSHOT_INVALID", assertThrows(VclException.class,
                    () -> worker.datasetDescription(account, active.epoch(), dataset, datasetRevision)).code());
            assertEquals("WORKER_SOURCE_NOT_PINNED", assertThrows(VclException.class,
                    () -> worker.capture(account, active.epoch(), UUID.randomUUID(), revision)).code());
            var changedGeneration = new String(body, StandardCharsets.UTF_8)
                    .replace(readiness.inputGeneration().toString(), UUID.randomUUID().toString());
            captureBody.set(changedGeneration.getBytes(StandardCharsets.UTF_8));
            assertEquals("WORKER_INPUT_CHANGED", assertThrows(VclException.class,
                    () -> worker.capture(account, active.epoch(), binding, revision)).code());
            captureBody.set(body);
            revokeDuringRead.set(true);
            assertEquals("DEPLOYMENT_CHANGED", assertThrows(VclException.class,
                    () -> worker.observations(account, active.epoch(), binding, revision, request)).code());
            assertEquals("DEPLOYMENT_INACTIVE", assertThrows(VclException.class,
                    () -> worker.capture(account, active.epoch(), binding, revision)).code());
        } finally { server.stop(0); }
    }
}
