package com.voltcanary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.alert.configurable.port.RuleReservationStore;
import com.voltcanary.common.test.SignedUserTokens;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:rule-reservations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "app.kafka.consume-enabled=false",
        "app.alert.authorization.enabled=false",
        "app.alert.notifications.worker-enabled=false",
        "app.alert.reconciliation.enabled=false",
    }
)
abstract class RuleReservationsContract extends SignedUserTest {

    private record Owner(UUID accountId, String status) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<UUID, Owner> OWNERS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> OWNER_WIRE_OVERRIDES = new ConcurrentHashMap<>();
    private static final AtomicInteger OWNER_READS = new AtomicInteger();
    private static final HttpServer OWNER;

    static {
        try {
            OWNER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            OWNER.createContext("/", exchange -> {
                OWNER_READS.incrementAndGet();
                int status = 200;
                byte[] bytes;
                if (
                    !"dev-internal-token".equals(
                        exchange.getRequestHeaders().getFirst("X-Internal-Service-Token")
                    )
                ) {
                    status = 401;
                    bytes = new byte[0];
                } else {
                    UUID actor = UUID.fromString(exchange.getRequestURI().getPath().split("/")[4]);
                    var owner = OWNERS.get(actor);
                    if (owner == null) {
                        status = 404;
                        bytes = new byte[0];
                    } else if (owner.status().equals("UNAVAILABLE")) {
                        status = 503;
                        bytes = "private upstream database detail".getBytes(StandardCharsets.UTF_8);
                    } else bytes = OWNER_WIRE_OVERRIDES.containsKey(actor)
                        ? OWNER_WIRE_OVERRIDES.get(actor).getBytes(StandardCharsets.UTF_8)
                        : MAPPER.writeValueAsBytes(
                              Map.of(
                                  "userId",
                                  actor,
                                  "accountId",
                                  owner.accountId(),
                                  "status",
                                  owner.status()
                              )
                          );
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            OWNER.start();
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void owner(DynamicPropertyRegistry registry) {
        registry.add(
            "app.alert.authorization.device-service-base-url",
            () -> "http://127.0.0.1:" + OWNER.getAddress().getPort()
        );
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RuleReservationStore store;

    @Test
    void signedReservationReplaysOriginalRuleOutputsReceiptAndAbsoluteExpiry() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        var first = reserve(actor, key, List.of("measure", "subject"));
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody().toString());
        assertEquals("RESERVED", first.getBody().path("status").asText());
        assertEquals(2, first.getBody().path("outputFieldIdsByKey").size());
        assertNotEquals(first.getBody().path("ruleId"), first.getBody().path("receiptId"));
        var replay = reserve(actor, key, List.of("subject", "measure"));
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(
            first.getBody(),
            inspect(actor, first.getBody().path("reviewId").asText()).getBody()
        );
        assertEquals(1, count(actor));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_rules WHERE account_id=?",
                Integer.class,
                OWNERS.get(actor).accountId()
            )
        );
    }

    @Test
    void changedPayloadConflictsAndOtherAccountDoesNotShareTheKeyNamespace() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        var first = reserve(actor, key, List.of("measure"));
        var conflict = reserve(actor, key, List.of("other"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.getBody().path("code").asText());
        UUID other = actor();
        var separate = reserve(other, key, List.of("measure"));
        assertEquals(HttpStatus.OK, separate.getStatusCode());
        assertNotEquals(first.getBody().path("ruleId"), separate.getBody().path("ruleId"));
        assertEquals(
            HttpStatus.NOT_FOUND,
            inspect(other, first.getBody().path("reviewId").asText()).getStatusCode()
        );
        assertEquals(
            HttpStatus.NOT_FOUND,
            inspect(other, UUID.randomUUID().toString()).getStatusCode()
        );
    }

    @Test
    void expirationDoesNotRenewTheOriginalIdsOrReservationAuthority() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        var first = reserve(actor, key, List.of()).getBody();
        OffsetDateTime expired = OffsetDateTime.now(java.time.ZoneOffset.UTC)
            .minusDays(1)
            .withNano(0);
        jdbc.update(
            "UPDATE cfg_rule_review_reservation SET expires_at=? WHERE id=?",
            expired,
            UUID.fromString(first.path("reviewId").asText())
        );
        var replay = reserve(actor, key, List.of()).getBody();
        assertEquals("EXPIRED", replay.path("status").asText());
        assertEquals(first.path("reviewId"), replay.path("reviewId"));
        assertEquals(first.path("ruleId"), replay.path("ruleId"));
        assertEquals(first.path("receiptId"), replay.path("receiptId"));
        assertEquals(
            expired.toInstant(),
            java.time.Instant.parse(replay.path("expiresAt").asText())
        );
    }

    @Test
    void sameIntentConcurrencyCommitsOneReservationEvenOnFirstAccountUse() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        var a = CompletableFuture.supplyAsync(() -> uncheckedReserve(actor, key, List.of("x")));
        var b = CompletableFuture.supplyAsync(() -> uncheckedReserve(actor, key, List.of("x")));
        var first = a.get(15, TimeUnit.SECONDS);
        var second = b.get(15, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody().toString());
        assertEquals(HttpStatus.OK, second.getStatusCode(), second.getBody().toString());
        assertEquals(first.getBody(), second.getBody());
        assertEquals(1, count(actor));
    }

    @Test
    void conflictingConcurrentIntentsHaveExactlyOneWinner() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        var a = CompletableFuture.supplyAsync(() -> uncheckedReserve(actor, key, List.of("x")));
        var b = CompletableFuture.supplyAsync(() -> uncheckedReserve(actor, key, List.of("y")));
        var outcomes = List.of(
            a.get(15, TimeUnit.SECONDS).getStatusCode(),
            b.get(15, TimeUnit.SECONDS).getStatusCode()
        );
        assertEquals(1, outcomes.stream().filter(HttpStatus.OK::equals).count());
        assertEquals(1, outcomes.stream().filter(HttpStatus.CONFLICT::equals).count());
        assertEquals(1, count(actor));
    }

    @Test
    void quotaIsSerializedAndExpiredReservationsReleaseOutstandingCapacity() throws Exception {
        UUID actor = actor();
        UUID account = OWNERS.get(actor).accountId();
        for (int index = 0; index < 100; index++) store.reserve(
            account,
            actor,
            List.of(),
            "quota-" + index
        );
        assertEquals(
            HttpStatus.TOO_MANY_REQUESTS,
            reserve(actor, "overflow", List.of()).getStatusCode()
        );
        jdbc.update(
            "UPDATE cfg_rule_review_reservation SET expires_at=? WHERE account_id=?",
            OffsetDateTime.now().minusDays(1),
            account
        );
        assertEquals(HttpStatus.OK, reserve(actor, "after-expiry", List.of()).getStatusCode());
        assertEquals(101, count(actor));
    }

    @Test
    void malformedOrAmplifiedRequestsFailBeforeOwnerIoAndNoReadProvisionsLocalScope() throws Exception {
        UUID actor = actor();
        int before = OWNER_READS.get();
        for (String body : List.of(
            "{\"kind\":\"RULE\",\"kind\":\"RULE\",\"outputKeys\":[],\"idempotencyKey\":\"x\"}",
            "{\"kind\":\"RULE\",\"outputKeys\":[\"a\",\"a\"],\"idempotencyKey\":\"x\"}",
            "{\"kind\":\"RULE\",\"outputKeys\":[\"bad-key\"],\"idempotencyKey\":\"x\"}",
            "{\"kind\":\"RULE\",\"outputKeys\":[],\"idempotencyKey\":\"\"}",
            "{\"kind\":\"RULE\",\"outputKeys\":[],\"idempotencyKey\":\"x\",\"approved\":true}",
            "{\"kind\":\"RULE\",\"outputKeys\":[],\"idempotencyKey\":\"x\",\"accountId\":\"foreign\"}"
        )) {
            assertEquals(
                HttpStatus.BAD_REQUEST,
                call(
                    actor,
                    HttpMethod.POST,
                    "/alert-rules/configurable/review-reservations",
                    body
                ).getStatusCode()
            );
        }
        assertEquals(before, OWNER_READS.get());
        assertEquals(
            HttpStatus.NOT_FOUND,
            inspect(actor, UUID.randomUUID().toString()).getStatusCode()
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cfg_rule_owner_fence WHERE account_id=?",
                Integer.class,
                OWNERS.get(actor).accountId()
            )
        );
        String unicode = "😀".repeat(160);
        assertEquals(HttpStatus.OK, reserve(actor, unicode, List.of()).getStatusCode());
    }

    @Test
    void signedIdentityAndFreshOwnerStateCannotBeBypassedByHeadersOrLegacyToggle() throws Exception {
        UUID actor = actor();
        var unsigned = new HttpHeaders();
        unsigned.set("X-User-Id", actor.toString());
        unsigned.set("X-Internal-Service-Token", "dev-internal-token");
        var denied = http.exchange(
            "/alert-rules/configurable/review-reservations",
            HttpMethod.POST,
            new HttpEntity<>("{}", unsigned),
            JsonNode.class
        );
        assertEquals(HttpStatus.UNAUTHORIZED, denied.getStatusCode());
        var created = reserve(actor, UUID.randomUUID().toString(), List.of()).getBody();
        OWNERS.put(actor, new Owner(OWNERS.get(actor).accountId(), "DISABLED"));
        assertEquals(
            HttpStatus.FORBIDDEN,
            inspect(actor, created.path("reviewId").asText()).getStatusCode()
        );
        assertEquals(
            HttpStatus.FORBIDDEN,
            reserve(actor, "after-revoke", List.of()).getStatusCode()
        );
        assertEquals(1, count(actor));
    }

    private UUID actor() {
        UUID actor = UUID.randomUUID();
        OWNERS.put(actor, new Owner(UUID.randomUUID(), "ACTIVE"));
        return actor;
    }

    @Test
    void failedOrMalformedAuthorityNeverFallsBackToLocalRowsOrLeaksItsBody() throws Exception {
        UUID actor = actor();
        var created = reserve(actor, UUID.randomUUID().toString(), List.of()).getBody();
        UUID account = OWNERS.get(actor).accountId();
        OWNER_WIRE_OVERRIDES.put(
            actor,
            MAPPER.writeValueAsString(
                Map.of("userId", UUID.randomUUID(), "accountId", account, "status", "ACTIVE")
            )
        );
        var invalid = inspect(actor, created.path("reviewId").asText());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, invalid.getStatusCode());
        assertEquals("ACCOUNT_OWNER_UNAVAILABLE", invalid.getBody().path("code").asText());
        OWNER_WIRE_OVERRIDES.remove(actor);
        OWNERS.put(actor, new Owner(account, "UNAVAILABLE"));
        var unavailable = reserve(actor, "must-not-create", List.of());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertTrue(!unavailable.getBody().toString().contains("private"));
        assertEquals(1, count(actor));
    }

    private int count(UUID actor) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM cfg_rule_review_reservation WHERE account_id=?",
            Integer.class,
            OWNERS.get(actor).accountId()
        );
    }

    private ResponseEntity<JsonNode> uncheckedReserve(
        UUID actor,
        String key,
        List<String> outputs
    ) {
        try {
            return reserve(actor, key, outputs);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ResponseEntity<JsonNode> reserve(
        UUID actor,
        String key,
        List<String> outputs
    ) throws java.io.IOException {
        return call(
            actor,
            HttpMethod.POST,
            "/alert-rules/configurable/review-reservations",
            MAPPER.writeValueAsString(
                Map.of("kind", "RULE", "outputKeys", outputs, "idempotencyKey", key)
            )
        );
    }

    private ResponseEntity<JsonNode> inspect(UUID actor, String id) {
        return call(actor, HttpMethod.GET, "/alert-rules/configurable/reviews/" + id, null);
    }

    private ResponseEntity<JsonNode> call(UUID actor, HttpMethod method, String path, String body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(
            SignedUserTokens.create(actor, "rule-reservation@example.invalid", "USER")
        );
        headers.set("X-User-Id", UUID.randomUUID().toString());
        headers.set("X-Account-Id", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
