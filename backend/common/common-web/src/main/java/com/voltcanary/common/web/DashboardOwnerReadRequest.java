package com.voltcanary.common.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Additive internal owner-read wire contract, with no executable query fields. */
public record DashboardOwnerReadRequest(UUID userId, UUID accountId, List<UUID> deviceIds,
        TimeFrame time, Set<String> metrics, List<UUID> detailDeviceIds, int rows, int offset, String incidentMode,
        List<Series> series,UUID workspaceId, Set<String> projections) {
    public DashboardOwnerReadRequest(UUID userId, UUID accountId, List<UUID> deviceIds,
            TimeFrame time, Set<String> metrics, List<UUID> detailDeviceIds, int rows, int offset, String incidentMode,
            List<Series> series, UUID workspaceId) {
        this(userId, accountId, deviceIds, time, metrics, detailDeviceIds, rows, offset, incidentMode, series, workspaceId, null);
    }
    public DashboardOwnerReadRequest(UUID userId,UUID accountId,List<UUID> deviceIds,TimeFrame time,Set<String> metrics,List<UUID> detailDeviceIds,int rows,int offset,String incidentMode,List<Series> series) {
        this(userId,accountId,deviceIds,time,metrics,detailDeviceIds,rows,offset,incidentMode,series,null);
    }
    public DashboardOwnerReadRequest {
        deviceIds = deviceIds == null ? List.of() : List.copyOf(deviceIds);
        metrics = metrics == null ? Set.of() : Set.copyOf(metrics);
        detailDeviceIds = detailDeviceIds == null ? List.of() : List.copyOf(detailDeviceIds);
        series = series == null ? List.of() : List.copyOf(series);
        projections = projections == null ? Set.of("latest", "summaries", "quality", "series") : Set.copyOf(projections);
    }

    public record TimeFrame(Instant from, Instant to, Instant asOf, Instant compareFrom, Instant compareTo) {
    }

    public record Series(UUID deviceId, String metric, UUID variableId, UUID dataSourceId) {
        public Series(UUID deviceId, String metric) { this(deviceId, metric, null, null); }
    }
}
