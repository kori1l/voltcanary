package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.analytics.vcl.adapter.JdbcCompilationBundleStore;
import com.voltcanary.analytics.vcl.adapter.JdbcRuleDeployments;
import com.voltcanary.analytics.vcl.adapter.NativeAsOfOwner;
import com.voltcanary.analytics.vcl.adapter.WorkerDatasetAsOfReads;
import com.voltcanary.analytics.vcl.adapter.WorkerSourceCaptureReads;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclParser;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WorkerDatasetAsOfReadsTest {

    @Test
    void signedManifestAndLookupUseOnlySealedProjectionAndRejectRevocation() throws Exception {
        var database = new DriverManagerDataSource(
            "jdbc:h2:mem:worker-asof-" +
                UUID.randomUUID() +
                ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
            "sa",
            ""
        );
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V034__vcl_compilation_bundles.sql"),
            new ClassPathResource("db/migration/V040__vcl_rule_deployments.sql"),
            new ClassPathResource("db/migration/V041__vcl_deployment_actor.sql"),
            new ClassPathResource("db/migration/V042__vcl_legacy_deployment_fence.sql"),
            new ClassPathResource("db/migration/V043__vcl_deployment_cutover.sql"),
            new ClassPathResource("db/migration/V051__vcl_rule_pause.sql")
        ).execute(database);
        var account = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var bundle = BundleFixtures.bundle(account, UUID.randomUUID(), "worker-asof");
        var bundles = new JdbcCompilationBundleStore(database);
        bundles.put(bundle);
        var registry = new JdbcRuleDeployments(database, bundles);
        var active = registry.activate(
            account,
            registry.stage(actor, bundle, UUID.randomUUID()).epoch(),
            ignored -> {}
        );
        var compilation = BundleFixtures.compilation("worker-asof");
        var node = compilation
            .graph()
            .stream()
            .filter(value -> value.method().equals("lookup_asof"))
            .findFirst()
            .orElseThrow();
        var config = node.config();
        var dataset = UUID.fromString(config.at("/target/datasetId").asText());
        long revision = config.at("/target/schemaRevision").asLong();
        var keyFields = new ArrayList<UUID>();
        var selected = new HashSet<String>();
        config.path("select").forEach(value -> selected.add(value.asText()));
        for (var pair : config.path("keyPairs")) {
            var key = UUID.fromString(pair.path("recordFieldId").asText());
            keyFields.add(key);
            selected.add(key.toString());
        }
        var interval = UUID.randomUUID();
        selected.add(interval.toString());
        var snapshot = UUID.randomUUID();
        var queryJson = new ObjectMapper()
            .createObjectNode()
            .put("language", "VCL/1")
            .put("kind", "QUERY");
        var spec = queryJson.putObject("spec");
        spec.set("target", config.path("target").deepCopy());
        var selection = spec.putArray("select");
        selected.stream().sorted().forEach(selection::add);
        spec.putObject("where")
            .put("op", "CONST")
            .putObject("literal")
            .put("type", "BOOLEAN")
            .put("value", true);
        spec.putArray("order")
            .addObject()
            .put("fieldId", keyFields.getFirst().toString())
            .put("direction", "ASC");
        spec.put("limit", 10).putNull("cursor");
        spec.putObject("read").put("mode", "SNAPSHOT").put("snapshotId", snapshot.toString());
        var query = new VclParser().parse(queryJson.toString());
        var manifest = new ObjectMapper()
            .createObjectNode()
            .put("datasetId", dataset.toString())
            .put("schemaRevision", revision)
            .put("snapshotId", snapshot.toString())
            .put("consistency", "SNAPSHOT")
            .put("generation", UUID.randomUUID().toString())
            .put("dataVersion", 1)
            .put("schemaDigest", "a".repeat(64))
            .put("accessScopeDigest", "b".repeat(64))
            .put("queryDigest", "c".repeat(64));
        var outcome = new ObjectMapper()
            .createObjectNode()
            .put("status", "UNKNOWN")
            .put("reason", "ASOF_MISSING")
            .putNull("row");
        outcome.set("readManifest", manifest.deepCopy());
        var expectedManifest = new VclParser().readJson(
            manifest.toString().getBytes(StandardCharsets.UTF_8)
        );
        var expectedOutcome = new VclParser().readJson(
            outcome.toString().getBytes(StandardCharsets.UTF_8)
        );
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var verifier = new VclWorkerAssertion(key, "worker-1", "device-service", Clock.systemUTC());
        var revoke = new AtomicBoolean();
        var driftManifest = new AtomicBoolean();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().toString();
            String operation = path.endsWith("query-asof-manifest")
                ? "DATASET_ASOF_MANIFEST"
                : "DATASET_ASOF";
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            var scope = verifier.verify(
                exchange.getRequestHeaders().getFirst("X-VCL-Worker-Assertion"),
                operation,
                dataset,
                VclWorkerAssertion.requestDigest("POST", path, requestBody)
            );
            assertEquals(actor, scope.actor());
            assertEquals(active.epoch(), scope.epoch());
            if (revoke.get()) registry.revoke(account, active.epoch());
            var answerNode = (ObjectNode) (
                operation.equals("DATASET_ASOF_MANIFEST") ? manifest : outcome
            ).deepCopy();
            if (driftManifest.get()) {
                var changed = operation.equals("DATASET_ASOF_MANIFEST")
                    ? answerNode
                    : (ObjectNode) answerNode.path("readManifest");
                changed.put("generation", UUID.randomUUID().toString());
            }
            byte[] answer = answerNode.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            try (var output = exchange.getResponseBody()) {
                output.write(answer);
            }
        });
        server.start();
        try {
            var properties = new AnalyticsDownstreamProperties();
            properties
                .getDeviceService()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getDeviceService().setTimeout(Duration.ofSeconds(2));
            var metadata = mock(WorkerSourceCaptureReads.class);
            var description = mock(VclDatasetDescription.class);
            when(description.schemaDigest()).thenReturn("a".repeat(64));
            when(description.accessScopeDigest()).thenReturn("b".repeat(64));
            when(description.generation()).thenReturn(
                UUID.fromString(manifest.path("generation").asText())
            );
            when(
                metadata.datasetDescription(account, active.epoch(), dataset, revision)
            ).thenReturn(description);
            var asOf = new NativeAsOfOwner(JacksonRestClients.builder(), properties);
            var worker = new WorkerDatasetAsOfReads(
                database,
                JacksonRestClients.builder(),
                properties,
                new VclWorkerIdentityProperties(
                    Base64.getEncoder().encodeToString(key),
                    "worker-1"
                ),
                metadata,
                asOf
            );
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            assertEquals(
                expectedManifest,
                worker.inspect(account, active.epoch(), query, interval, keyFields, deadline)
            );
            var eventFields = new java.util.HashMap<
                UUID,
                com.fasterxml.jackson.databind.JsonNode
            >();
            for (var pair : config.path("keyPairs")) {
                eventFields.put(
                    UUID.fromString(pair.path("eventFieldId").asText()),
                    new ObjectMapper()
                        .createObjectNode()
                        .put("type", "TEXT")
                        .put("value", "cabinet")
                );
            }
            assertEquals(
                expectedOutcome,
                worker.lookup(
                    account,
                    active.epoch(),
                    node,
                    query,
                    interval,
                    eventFields,
                    Instant.parse("2026-11-07T00:00:00Z"),
                    expectedManifest,
                    deadline
                )
            );
            assertEquals(2, calls.get());
            var changed = (ObjectNode) queryJson.deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode) changed.at("/spec/select")).remove(0);
            var drifted = new VclParser().parse(changed.toString());
            assertEquals(
                "WORKER_DATASET_NOT_PINNED",
                assertThrows(VclException.class, () ->
                    worker.inspect(account, active.epoch(), drifted, interval, keyFields, deadline)
                ).code()
            );
            assertEquals(2, calls.get());
            driftManifest.set(true);
            assertEquals(
                "OWNER_SNAPSHOT_INVALID",
                assertThrows(VclException.class, () ->
                    worker.inspect(account, active.epoch(), query, interval, keyFields, deadline)
                ).code()
            );
            driftManifest.set(false);
            revoke.set(true);
            assertEquals(
                "DEPLOYMENT_CHANGED",
                assertThrows(VclException.class, () ->
                    worker.inspect(account, active.epoch(), query, interval, keyFields, deadline)
                ).code()
            );
        } finally {
            server.stop(0);
        }
    }
}
