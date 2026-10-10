package com.voltcanary.dashboard.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.port.DashboardEvidenceReads;
import com.voltcanary.dashboard.port.DashboardEvidenceReads.ReadRequest;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Six owner reads at most, shared by every query in this JVM; request-local single flight. */
@Component
public class QueryReadScheduler {
    public record Key(String owner, ReadRequest request) { }
    private final DashboardEvidenceReads reads;
    private final ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(6, 6, 0, TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(144), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    public QueryReadScheduler(DashboardEvidenceReads reads) { this.reads = reads; }
    public CompletableFuture<JsonNode> read(String owner, ReadRequest request, String authorization,
            DashboardActor actor, Map<Key, CompletableFuture<JsonNode>> memo) {
        return memo.computeIfAbsent(new Key(owner, request), key -> CompletableFuture.supplyAsync(() -> switch (owner) {
            case "telemetry" -> reads.telemetry(authorization, actor, request);
            case "energy" -> reads.energy(authorization,actor,request);
            case "energy-validation" -> reads.energyValidation(authorization,actor,request);
            case "analytics" -> reads.analytics(authorization, actor, request);
            case "incidents" -> reads.incidents(authorization, actor, request);
            case "maintenance" -> reads.maintenance(authorization, actor, request);
            default -> throw new IllegalArgumentException("Unknown read owner");
        }, pool));
    }
    @PreDestroy public void close() { pool.shutdownNow(); }
}
