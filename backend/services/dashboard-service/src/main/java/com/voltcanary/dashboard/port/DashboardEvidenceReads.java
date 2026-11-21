package com.voltcanary.dashboard.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.common.web.EnergyMeterDefinition;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.query.QueryTypes.Frame;
import com.voltcanary.dashboard.query.QueryTypes;
import com.voltcanary.dashboard.query.QueryTypes.Scope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit owner read ports; no arbitrary endpoint, SQL or write operation. */
public interface DashboardEvidenceReads {
    default JsonNode nativeRows(String authorization, JsonNode document) {
        throw new com.voltcanary.dashboard.domain.DashboardFailure("DEPENDENCY_UNAVAILABLE", "Table owner is unavailable");
    }
    default JsonNode ruleMeasure(String authorization, DashboardActor actor, JsonNode selection) {
        throw new com.voltcanary.dashboard.domain.DashboardFailure("DEPENDENCY_UNAVAILABLE", "Analytics owner is unavailable");
    }
    DashboardActor activeActor(String authorization, UUID userId);
    record Device(UUID id, String name, String type, UUID locationId, String registrationStatus,
            Long cadenceSeconds, String cadenceSource, String sourcePolicy, Instant updatedAt,List<EnergyMeterDefinition> energyMeters) {
        public Device(UUID id,String name,String type,UUID locationId,String registrationStatus,Long cadenceSeconds,
                String cadenceSource,String sourcePolicy,Instant updatedAt) {
            this(id,name,type,locationId,registrationStatus,cadenceSeconds,cadenceSource,sourcePolicy,updatedAt,List.of());
        }
    }

    record Location(UUID id, String name, UUID parentId, List<String> tags) {
    }

    record ScopeSnapshot(UUID userId, UUID accountId, long requestedCount, long evaluatedCount,
            long deniedBindingCount, List<Device> devices, List<Location> locations,
            boolean deviceTagsSupported, Instant resolvedAt,UUID workspaceId,String authorizationFingerprint) {
        public ScopeSnapshot(UUID userId,UUID accountId,long requestedCount,long evaluatedCount,long deniedBindingCount,List<Device> devices,List<Location> locations,boolean deviceTagsSupported,Instant resolvedAt) {
            this(userId,accountId,requestedCount,evaluatedCount,deniedBindingCount,devices,locations,deviceTagsSupported,resolvedAt,null,null);
        }
    }

    record ReadRequest(List<UUID> deviceIds, UUID accountId, Frame time, Set<String> metrics,
            List<UUID> detailDeviceIds, int rows, int offset, String incidentMode, List<QueryTypes.Series> series,boolean independentMetersConfirmed,UUID workspaceId,Set<String> projections, String sourceId, java.util.Map<UUID,String> vehicleSources) {
        public ReadRequest(List<UUID> deviceIds,UUID accountId,Frame time,Set<String> metrics,List<UUID> detailDeviceIds,
                int rows,int offset,String incidentMode,List<QueryTypes.Series> series,boolean independentMetersConfirmed,
                UUID workspaceId,Set<String> projections,String sourceId) {
            this(deviceIds,accountId,time,metrics,detailDeviceIds,rows,offset,incidentMode,series,independentMetersConfirmed,workspaceId,projections,sourceId,null);
        }
        public ReadRequest(List<UUID> deviceIds,UUID accountId,Frame time,Set<String> metrics,List<UUID> detailDeviceIds,
                int rows,int offset,String incidentMode,List<QueryTypes.Series> series,boolean independentMetersConfirmed,
                UUID workspaceId,Set<String> projections) {
            this(deviceIds,accountId,time,metrics,detailDeviceIds,rows,offset,incidentMode,series,independentMetersConfirmed,workspaceId,projections,null);
        }
        public ReadRequest(List<UUID> deviceIds, UUID accountId, Frame time, Set<String> metrics,
                List<UUID> detailDeviceIds, int rows, int offset, String incidentMode, List<QueryTypes.Series> series, boolean independentMetersConfirmed, UUID workspaceId) {
            this(deviceIds, accountId, time, metrics, detailDeviceIds, rows, offset, incidentMode, series, independentMetersConfirmed, workspaceId,
                    Set.of("latest", "summaries", "quality", "series"));
        }
        public ReadRequest(List<UUID> deviceIds,UUID accountId,Frame time,Set<String> metrics,List<UUID> detailDeviceIds,int rows,int offset,String incidentMode,List<QueryTypes.Series> series,boolean independentMetersConfirmed) {
            this(deviceIds,accountId,time,metrics,detailDeviceIds,rows,offset,incidentMode,series,independentMetersConfirmed,null);
        }
        public ReadRequest(List<UUID> deviceIds,UUID accountId,Frame time,Set<String> metrics,List<UUID> detailDeviceIds,
                int rows,int offset,String incidentMode,List<QueryTypes.Series> series) {
            this(deviceIds,accountId,time,metrics,detailDeviceIds,rows,offset,incidentMode,series,false);
        }
    }

    ScopeSnapshot scope(String authorization, DashboardActor actor, Scope scope);
    default ScopeSnapshot scopeForAction(String authorization,DashboardActor actor,Scope scope,String action) {
        return scope(authorization,actor,scope);
    }
    JsonNode telemetry(String authorization, DashboardActor actor, ReadRequest request);
    default JsonNode positions(String authorization, DashboardActor actor, ReadRequest request) {
        throw new com.voltcanary.dashboard.domain.DashboardFailure("DEPENDENCY_UNAVAILABLE", "Position evidence unavailable");
    }
    JsonNode energy(String authorization, DashboardActor actor, ReadRequest request);
    JsonNode energyValidation(String authorization, DashboardActor actor, ReadRequest request);
    JsonNode analytics(String authorization, DashboardActor actor, ReadRequest request);
    JsonNode incidents(String authorization, DashboardActor actor, ReadRequest request);
    JsonNode maintenance(String authorization, DashboardActor actor, ReadRequest request);
    default JsonNode businessVersion(String authorization, DashboardActor actor, ReadRequest request, String owner) {
        return null;
    }
    default JsonNode cost(String authorization,DashboardActor actor,ReadRequest request) {
        throw new com.voltcanary.dashboard.domain.DashboardFailure("DEPENDENCY_UNAVAILABLE","Tariff evidence unavailable");
    }
    default JsonNode operations(String authorization,DashboardActor actor,ReadRequest request) {
        throw new com.voltcanary.dashboard.domain.DashboardFailure("DEPENDENCY_UNAVAILABLE","Operational evidence unavailable");
    }
}
