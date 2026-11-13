package com.voltcanary.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.voltcanary.vcl.VclObservationProfile;
import com.voltcanary.vcl.VclObservationReadiness;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Controlled internal dependency. Real generation SQL/HTTP behavior is verified in telemetry-service. */
final class CaptureJournalFixture {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final AtomicInteger READS = new AtomicInteger();
    static final AtomicReference<Runnable> AFTER_READ = new AtomicReference<>();
    static volatile Consumer<ObjectNode> mutate;
    static volatile int status = 200;
    static volatile String state = "READY";
    static volatile java.util.function.Function<com.voltcanary.vcl.VclObservationRead, com.voltcanary.vcl.VclObservationPage> page;
    static final HttpServer SERVER;
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                READS.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                boolean coverage = path.startsWith("/internal/observations/source-coverage/") && path.endsWith("/read");
                if (!(path.equals("/internal/observations/binding-readiness") || path.equals("/internal/observations/read") || coverage)
                        || !exchange.getRequestMethod().equals("POST") || !"dev-internal-token".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"))) {
                    exchange.sendResponseHeaders(401, -1); exchange.close(); return;
                }
                ObjectNode value;
                if (path.equals("/internal/observations/read")) {
                    var request = com.voltcanary.vcl.VclObservationRead.parse(exchange.getRequestBody().readAllBytes());
                    value = MAPPER.valueToTree(page.apply(request));
                } else if (coverage) {
                    var pin = VclObservationReadiness.parsePin(exchange.getRequestBody().readAllBytes());
                    value = MAPPER.createObjectNode().put("proofId", path.split("/")[4]).put("snapshotId", pin.snapshotId().toString())
                            .put("coverage", "UNKNOWN").put("reason", "PROOF_UNAVAILABLE")
                            .putNull("inputGeneration").putNull("requiredCheckpoint").putNull("evidence");
                } else {
                    var pin = VclObservationReadiness.parsePin(exchange.getRequestBody().readAllBytes());
                    var readiness = state.equals("READY") ? new VclObservationReadiness(pin, VclObservationProfile.VERSION, state,
                            UUID.randomUUID(), "0", "1", Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS), true,
                            false, false, "UNKNOWN", "UNKNOWN", "UNQUALIFIED", null) : VclObservationReadiness.absent(pin, state);
                    value = MAPPER.valueToTree(readiness);
                }
                var change = mutate; if (change != null) change.accept(value);
                Runnable after = AFTER_READ.getAndSet(null); if (after != null) after.run();
                byte[] bytes = status == 200 ? MAPPER.writeValueAsBytes(value) : "private capture owner detail".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Location", "/redirected");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            SERVER.start();
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    static void reset() { mutate = null; status = 200; state = "READY"; page = null; AFTER_READ.set(null); }
    private CaptureJournalFixture() { }
}
