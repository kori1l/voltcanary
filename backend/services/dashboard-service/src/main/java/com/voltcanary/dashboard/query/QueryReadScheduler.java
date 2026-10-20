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
    public JsonNode businessVersion(String authorization, DashboardActor actor, ReadRequest request, String owner) {
        return reads.businessVersion(authorization, actor, request, owner);
    }
    public CompletableFuture<JsonNode> read(String owner, ReadRequest request, String authorization,
            DashboardActor actor, Map<Key, CompletableFuture<JsonNode>> memo) {
        ReadRequest normalized = normalize(owner, request);
        return memo.computeIfAbsent(new Key(owner, normalized), key -> CompletableFuture.supplyAsync(() -> execute(owner, normalized, authorization, actor), pool));
    }
    private JsonNode execute(String owner, ReadRequest request, String authorization, DashboardActor actor) {
        return switch (owner) {
            case "positions" -> reads.positions(authorization, actor, request);
            case "telemetry" -> reads.telemetry(authorization, actor, request);
            case "energy" -> reads.energy(authorization,actor,request);
            case "energy-validation" -> reads.energyValidation(authorization,actor,request);
            case "analytics" -> reads.analytics(authorization, actor, request);
            case "incidents" -> reads.incidents(authorization, actor, request);
            case "maintenance" -> reads.maintenance(authorization, actor, request);
            case "cost" -> reads.cost(authorization,actor,request);
            case "operations" -> reads.operations(authorization,actor,request);
            default -> throw new IllegalArgumentException("Unknown read owner");
        };
    }
    private ReadRequest normalize(String owner, ReadRequest request) {
        boolean paged = java.util.List.of("incidents", "maintenance").contains(owner);
        boolean telemetry = "telemetry".equals(owner);
        var frame=request.time();
        boolean latestOnly=telemetry && request.projections().equals(java.util.Set.of("latest"));
        boolean seriesRead=telemetry && request.projections().contains("series");
        if (latestOnly) frame=new com.voltcanary.dashboard.query.QueryTypes.Frame(frame.asOf().minusSeconds(1),frame.asOf(),frame.asOf(),"UTC",false,null,null,"NONE");
        return new ReadRequest(request.deviceIds(), request.accountId(), frame, telemetry && !latestOnly ? request.metrics() : java.util.Set.of(),
                seriesRead ? request.series().stream().map(QueryTypes.Series::deviceId).distinct().sorted().toList() : java.util.List.of(), paged ? request.rows() : 1, paged ? request.offset() : 0,
                "incidents".equals(owner) ? request.incidentMode() : "ACTIVE", seriesRead ? request.series() : java.util.List.of(),
                java.util.List.of("energy", "energy-validation", "cost", "operations").contains(owner) && request.independentMetersConfirmed(), request.workspaceId(),
                telemetry ? request.projections() : java.util.Set.of(), request.sourceId(), request.vehicleSources());
    }
    @PreDestroy public void close() { pool.shutdownNow(); }
}
