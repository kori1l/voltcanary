package com.voltcanary.analytics.vcl;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.analytics.config.VclWorkerIdentityProperties;
import com.voltcanary.analytics.vcl.adapter.JdbcCompilationBundleStore;
import com.voltcanary.analytics.vcl.adapter.JdbcRuleDeployments;
import com.voltcanary.analytics.vcl.adapter.WorkerGrantReads;
import com.voltcanary.analytics.vcl.adapter.WorkerPolicyReads;
import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.vcl.VclCanonical;
import com.voltcanary.vcl.VclException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WorkerGrantReadsTest {

    @Test
    void sealedGrantPinIsRenewedAndRevocationDuringOwnerResponseFailsClosed() throws Exception {
        var database = new DriverManagerDataSource(
            "jdbc:h2:mem:worker-grant-" +
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
        var compilation = BundleFixtures.compilation("machine-grant");
        var rule = UUID.fromString(
            new com.voltcanary.vcl.VclParser()
                .readJson(compilation.canonicalDocument().getBytes(StandardCharsets.UTF_8))
                .at("/spec/ruleId")
                .asText()
        );
        var grant = BundleFixtures.GRANT;
        var expiry = Instant.now()
            .plusSeconds(3600)
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var definition = new ObjectMapper().createObjectNode().put("expiresAt", expiry.toString());
        definition
            .putArray("sourceBindings")
            .addObject()
            .put("id", UUID.randomUUID().toString())
            .put("schemaRevision", 1)
            .putArray("fieldIds")
            .add(UUID.randomUUID().toString());
        definition.putArray("datasets");
        var definitionDigest = VclCanonical.sha256(VclCanonical.encode(definition));
        var grantDigest = VclCanonical.sha256(
            "AUTOMATION_GRANT/1:" +
                grant +
                ":" +
                account +
                ":" +
                actor +
                ":" +
                rule +
                ":1:ACTIVE:" +
                definitionDigest
        );
        var policy = UUID.fromString("00000000-0000-4000-8000-000000000302");
        var policyDefinition = new ObjectMapper()
            .createObjectNode()
            .put("mode", "PRIVATE_REFERENCES")
            .put("retentionDays", 1)
            .put("maxObservationReferences", 1);
        var policyDefinitionDigest = VclCanonical.sha256(VclCanonical.encode(policyDefinition));
        var policyDigest = VclCanonical.sha256(
            "EVIDENCE_POLICY/1:" +
                policy +
                ":" +
                account +
                ":" +
                actor +
                ":" +
                rule +
                ":" +
                grant +
                ":1:" +
                definitionDigest +
                ":" +
                expiry +
                ":" +
                policyDefinitionDigest
        );
        var policyJson = new ObjectMapper()
            .createObjectNode()
            .put("id", policy.toString())
            .put("accountId", account.toString())
            .put("actorId", actor.toString())
            .put("ruleId", rule.toString())
            .put("grantId", grant.toString())
            .put("grantEpoch", "1")
            .put("grantDefinitionDigest", definitionDigest)
            .put("profile", "private-reference-evidence-1")
            .put("revision", 1)
            .put("status", "ACTIVE")
            .put("usable", true)
            .put("expiresAt", expiry.toString())
            .put("definitionDigest", policyDefinitionDigest);
        policyJson.set("definition", policyDefinition);
        byte[] policyBody = policyJson.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(
            policy,
            com.voltcanary.vcl.VclEvidencePolicyDescription.parse(policyBody).id()
        );
        var json = new ObjectMapper()
            .createObjectNode()
            .put("id", grant.toString())
            .put("accountId", account.toString())
            .put("actorId", actor.toString())
            .put("ruleId", rule.toString())
            .put("profile", "source-native-fields-grant-1")
            .put("epoch", "1")
            .put("status", "ACTIVE")
            .put("expiresAt", expiry.toString())
            .put("definitionDigest", definitionDigest);
        json.set("definition", definition);
        json.putArray("effects").add("ALERT");
        json.putArray("deliveryChannels");
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(grant, com.voltcanary.vcl.VclAutomationGrantDescription.parse(body).id());
        var base = BundleFixtures.validation(compilation);
        var execution = base.execution();
        var pins = execution
            .resourceRevisions()
            .stream()
            .map(pin -> {
                if (pin.kind() == CompilationOwnerValidation.ResourceKind.EXECUTION_GRANT) {
                    return new CompilationOwnerValidation.ResourcePin(
                        pin.kind(),
                        pin.id(),
                        1,
                        grantDigest
                    );
                }
                if (pin.kind() == CompilationOwnerValidation.ResourceKind.EVIDENCE_POLICY) {
                    return new CompilationOwnerValidation.ResourcePin(
                        pin.kind(),
                        pin.id(),
                        1,
                        policyDigest
                    );
                }
                return pin;
            })
            .toList();
        var updatedExecution = new CompilationOwnerValidation.ExecutionPins(
            execution.grantId(),
            execution.bindingGenerations(),
            execution.startBoundary(),
            execution.keyDefinitionDigest(),
            execution.snapshotSetDigest(),
            pins,
            execution.spatialKernelVersion(),
            execution.operatorVersions()
        );
        var validation = new CompilationOwnerValidation(
            base.documentDigest(),
            base.accessScopeDigest(),
            base.dependencies(),
            updatedExecution,
            base.cost()
        );
        var bundle = CompilationBundle.seal(account, UUID.randomUUID(), compilation, validation);
        var bundles = new JdbcCompilationBundleStore(database);
        bundles.put(bundle);
        var registry = new JdbcRuleDeployments(database, bundles);
        var active = registry.activate(
            account,
            registry.stage(actor, bundle, grant).epoch(),
            ignored -> {}
        );
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var verifier = new VclWorkerAssertion(key, "worker-1", "alert-service", Clock.systemUTC());
        var revokePolicy = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().toString();
            boolean policyRead = path.startsWith("/internal/vcl/evidence-policies/");
            var scope = verifier.verify(
                exchange.getRequestHeaders().getFirst("X-VCL-Worker-Assertion"),
                policyRead ? "EVIDENCE_POLICY" : "GRANT_DESCRIPTION",
                policyRead ? policy : grant,
                VclWorkerAssertion.requestDigest(exchange.getRequestMethod(), path, new byte[0])
            );
            assertEquals(actor, scope.actor());
            assertEquals(active.epoch(), scope.epoch());
            assertEquals(rule, scope.rule());
            if (policyRead && revokePolicy.get()) registry.revoke(account, active.epoch());
            byte[] answer = policyRead ? policyBody : body;
            exchange.sendResponseHeaders(200, answer.length);
            try (var output = exchange.getResponseBody()) {
                output.write(answer);
            }
        });
        server.start();
        try {
            var properties = new AnalyticsDownstreamProperties();
            properties
                .getAlertService()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getAlertService().setTimeout(Duration.ofSeconds(2));
            var worker = new WorkerGrantReads(
                database,
                JacksonRestClients.builder(),
                properties,
                new VclWorkerIdentityProperties(Base64.getEncoder().encodeToString(key), "worker-1")
            );
            assertEquals(grant, worker.read(account, active.epoch()).id());
            var policies = new WorkerPolicyReads(
                database,
                JacksonRestClients.builder(),
                properties,
                new VclWorkerIdentityProperties(
                    Base64.getEncoder().encodeToString(key),
                    "worker-1"
                ),
                worker
            );
            assertEquals(policy, policies.read(account, active.epoch(), policy).id());
            assertEquals(
                "WORKER_POLICY_NOT_PINNED",
                assertThrows(VclException.class, () ->
                    policies.read(account, active.epoch(), UUID.randomUUID())
                ).code()
            );
            revokePolicy.set(true);
            assertEquals(
                "DEPLOYMENT_CHANGED",
                assertThrows(VclException.class, () ->
                    policies.read(account, active.epoch(), policy)
                ).code()
            );
            assertEquals(
                "DEPLOYMENT_INACTIVE",
                assertThrows(VclException.class, () -> worker.read(account, active.epoch())).code()
            );
        } finally {
            server.stop(0);
        }
    }
}
