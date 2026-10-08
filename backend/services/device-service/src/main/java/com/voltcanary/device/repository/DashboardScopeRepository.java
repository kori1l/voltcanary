package com.voltcanary.device.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.dto.DashboardScopeRequest;
import com.voltcanary.device.dto.DashboardScopeResponse;
import com.voltcanary.device.dto.DashboardScopeResponse.DeviceEntry;
import com.voltcanary.device.dto.DashboardScopeResponse.LocationEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class DashboardScopeRepository {
    private record SourceCadence(Long seconds, String policy) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DashboardScopeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardScopeResponse resolve(UUID userId, UUID accountId, DashboardScopeRequest request) {
        if ("UNBOUND".equals(request.mode())) {
            return new DashboardScopeResponse(userId, accountId, 0, 0, 0, List.of(), List.of(), false, Instant.now());
        }
        long denied = deniedBindings(accountId, request.deviceIds(), "devices")
                + deniedBindings(accountId, request.locationIds(), "locations");
        Set<UUID> locations = request.includeSubtree() ? subtree(accountId, request.locationIds()) : request.locationIds();
        List<Object> parameters = new ArrayList<>(List.of(accountId));
        String where = " WHERE d.account_id=?";
        if (!request.deviceIds().isEmpty()) {
            where += " AND d.id IN (" + placeholders(request.deviceIds().size()) + ")";
            parameters.addAll(request.deviceIds());
        }
        if (!request.locationIds().isEmpty()) {
            if (locations.isEmpty()) {
                where += " AND false";
            } else {
                where += " AND d.location_id IN (" + placeholders(locations.size()) + ")";
                parameters.addAll(locations);
            }
        }
        if (!request.deviceTypes().isEmpty()) {
            where += " AND t.code IN (" + placeholders(request.deviceTypes().size()) + ")";
            parameters.addAll(request.deviceTypes());
        }
        if (!request.locationTags().isEmpty()) {
            where += " AND jsonb_exists_any(coalesce(l.tags_json,'[]')::jsonb, ARRAY["
                    + placeholders(request.locationTags().size()) + "]::text[])";
            parameters.addAll(request.locationTags());
        }
        String joined = " FROM devices d JOIN device_types t ON t.id=d.device_type_id LEFT JOIN locations l ON l.id=d.location_id AND l.account_id=d.account_id";
        long count = jdbc.queryForObject("SELECT count(*)" + joined + where, Long.class, parameters.toArray());
        if (count > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Scope exceeds500 devices; choose a narrower binding");
        }
        List<DeviceEntry> devices = jdbc.query("SELECT d.id,d.name,t.code,d.location_id,d.status,d.updated_at" + joined + where
                + " ORDER BY d.created_at,d.id", (row, index) -> new DeviceEntry(row.getObject("id", UUID.class),
                row.getString("name"), row.getString("code"), row.getObject("location_id", UUID.class), row.getString("status"),
                null, "dashboard_default", "PERIODIC", row.getTimestamp("updated_at").toInstant()), parameters.toArray());
        Map<UUID, SourceCadence> cadences = cadences(devices.stream().map(DeviceEntry::id).toList());
        List<DeviceEntry> enriched = devices.stream().map(device -> {
            SourceCadence cadence = cadences.get(device.id());
            return new DeviceEntry(device.id(), device.name(), device.type(), device.locationId(), device.registrationStatus(),
                    cadence == null ? null : cadence.seconds(), cadence != null && cadence.seconds() != null ? "source_configuration" : "dashboard_default",
                    cadence == null ? "PERIODIC" : cadence.policy(), device.updatedAt());
        }).toList();
        Set<UUID> relevantLocations = new HashSet<>(request.locationIds());
        devices.stream().map(DeviceEntry::locationId).filter(java.util.Objects::nonNull).forEach(relevantLocations::add);
        List<LocationEntry> metadata = locationMetadata(accountId, relevantLocations);
        return new DashboardScopeResponse(userId, accountId, count + denied, count, denied, enriched, metadata, false, Instant.now());
    }

    private long deniedBindings(UUID accountId, Set<UUID> ids, String table) {
        if (ids.isEmpty()) {
            return 0;
        }
        List<Object> args = new ArrayList<>(List.of(accountId));
        args.addAll(ids);
        long owned = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id=? AND id IN ("
                + placeholders(ids.size()) + ")", Long.class, args.toArray());
        return ids.size() - owned;
    }

    private Set<UUID> subtree(UUID accountId, Set<UUID> roots) {
        if (roots.isEmpty()) {
            return Set.of();
        }
        List<Object> args = new ArrayList<>(List.of(accountId));
        args.addAll(roots);
        args.add(accountId);
        return new HashSet<>(jdbc.query("""
                WITH RECURSIVE tree AS (
                  SELECT id FROM locations WHERE account_id=? AND id IN (
                """ + placeholders(roots.size()) + """
                ) UNION SELECT l.id FROM locations l JOIN tree p ON l.parent_id=p.id WHERE l.account_id=?
                ) SELECT id FROM tree
                """, (row, index) -> row.getObject("id", UUID.class), args.toArray()));
    }

    private List<LocationEntry> locationMetadata(UUID accountId, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(List.of(accountId));
        args.addAll(ids);
        return jdbc.query("SELECT id,name,parent_id,tags_json FROM locations WHERE account_id=? AND id IN ("
                + placeholders(ids.size()) + ") ORDER BY id", (row, index) -> new LocationEntry(row.getObject("id", UUID.class),
                row.getString("name"), row.getObject("parent_id", UUID.class), tags(row.getString("tags_json"))), args.toArray());
    }

    private Map<UUID, SourceCadence> cadences(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<SourceCadence>> sources = new HashMap<>();
        jdbc.query("SELECT device_id,type,adapter_config_json FROM data_sources WHERE enabled=true AND status='ACTIVE' AND device_id IN ("
                + placeholders(ids.size()) + ")", row -> {
            UUID id = row.getObject("device_id", UUID.class);
            String policy = "CSV".equals(row.getString("type")) ? "MANUAL" : "PERIODIC";
            sources.computeIfAbsent(id, unused -> new ArrayList<>()).add(new SourceCadence(interval(row.getString("adapter_config_json")), policy));
        }, ids.toArray());
        Map<UUID, SourceCadence> result = new HashMap<>();
        sources.forEach((id, entries) -> {
            String policy = entries.stream().allMatch(entry -> "MANUAL".equals(entry.policy())) ? "MANUAL" : "PERIODIC";
            Long seconds = entries.stream().filter(entry -> "PERIODIC".equals(entry.policy())).map(SourceCadence::seconds)
                    .filter(java.util.Objects::nonNull).min(Long::compareTo).orElse(null);
            result.put(id, new SourceCadence(seconds, policy));
        });
        return result;
    }

    private Long interval(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode config = mapper.readTree(json);
            for (String key : List.of("expectedIntervalSeconds", "samplingIntervalSeconds")) {
                if (config.path(key).isNumber()) {
                    long value = config.path(key).asLong();
                    if (value > 0 && value <= 86400) return value;
                }
            }
            for (String key : List.of("pollIntervalMs", "samplingIntervalMs", "intervalMillis")) {
                if (config.path(key).isNumber()) {
                    long value = config.path(key).asLong();
                    if (value > 0 && value <= 86400000) return Math.max(1, (value + 999) / 1000);
                }
            }
        } catch (Exception ignored) {
            // Malformed configuration is unknown cadence; never expose its content.
        }
        return null;
    }

    private List<String> tags(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return mapper.readerForListOf(String.class).readValue(json);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
