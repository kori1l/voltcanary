package com.voltcanary.device.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.device.audit.DeviceAuditService;
import com.voltcanary.device.domain.Account;
import com.voltcanary.device.domain.Device;
import com.voltcanary.device.domain.Location;
import com.voltcanary.device.dto.LocationRequest;
import com.voltcanary.device.dto.LocationResponse;
import com.voltcanary.device.event.DeviceRegistryEventPublisher;
import com.voltcanary.device.repository.LocationRepository;
import com.voltcanary.device.security.AuthenticatedUser;
import com.voltcanary.device.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class LocationService {

    private final AccountService accountService;
    private final LocationRepository locationRepository;
    private final DeviceRegistryEventPublisher eventPublisher;
    private final DeviceAuditService auditService;
    private final ObjectMapper objectMapper;

    public LocationService(
            AccountService accountService,
            LocationRepository locationRepository,
            DeviceRegistryEventPublisher eventPublisher,
            DeviceAuditService auditService,
            ObjectMapper objectMapper) {
        this.accountService = accountService;
        this.locationRepository = locationRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LocationResponse create(AuthenticatedUser user, LocationRequest request) {
        Account account = accountService.currentAccount(user);
        Instant now = Instant.now();

        Location location = new Location();
        location.setId(UUID.randomUUID());
        location.setAccountId(account.getId());
        applyRequest(user, location, request);
        location.setCreatedAt(now);
        location.setUpdatedAt(now);

        Location saved = locationRepository.save(location);
        auditService.record(user, account.getId(), "LOCATION_CREATED", "LOCATION", saved.getId());
        eventPublisher.publish("LOCATION", "CREATED", account.getId(), null, saved.getId(), null);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> list(AuthenticatedUser user) {
        return list(user, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> list(AuthenticatedUser user, String search, Integer limit, Integer offset) {
        Account account = accountService.currentAccount(user);
        return locationRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .filter(location -> matchesSearch(location, search))
                .skip(normalizedOffset(offset))
                .limit(normalizedLimit(limit))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public LocationResponse get(AuthenticatedUser user, UUID id) {
        return toResponse(findOwned(user, id));
    }

    @Transactional
    public LocationResponse update(AuthenticatedUser user, UUID id, LocationRequest request) {
        Location location = findOwned(user, id);
        applyRequest(user, location, request);
        location.setUpdatedAt(Instant.now());
        Location saved = locationRepository.save(location);
        auditService.record(user, saved.getAccountId(), "LOCATION_UPDATED", "LOCATION", saved.getId());
        eventPublisher.publish("LOCATION", "UPDATED", saved.getAccountId(), null, saved.getId(), null);
        return toResponse(saved);
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID id) {
        Location location = findOwned(user, id);
        locationRepository.delete(location);
        auditService.record(user, location.getAccountId(), "LOCATION_DELETED", "LOCATION", location.getId());
        eventPublisher.publish("LOCATION", "DELETED", location.getAccountId(), null, location.getId(), null);
    }

    public Location findOwned(AuthenticatedUser user, UUID id) {
        Account account = accountService.currentAccount(user);
        return locationRepository.findByIdAndAccountId(id, account.getId())
                .orElseThrow(() -> new NotFoundException("Location not found"));
    }

    private LocationResponse toResponse(Location location) {
        return new LocationResponse(
                location.getId(),
                location.getName(),
                location.getDescription(),
                location.getParentId(),
                location.getBuilding(),
                location.getFloor(),
                location.getRoom(),
                location.getZone(),
                fromTagsJson(location.getTagsJson()));
    }

    private boolean matchesSearch(Location location, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.trim().toLowerCase();
        return contains(location.getName(), needle)
                || contains(location.getDescription(), needle)
                || contains(location.getBuilding(), needle)
                || contains(location.getFloor(), needle)
                || contains(location.getRoom(), needle)
                || contains(location.getZone(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private long normalizedOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        return offset;
    }

    private long normalizedLimit(Integer limit) {
        if (limit == null) {
            return Long.MAX_VALUE;
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return limit;
    }

    private String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void applyRequest(AuthenticatedUser user, Location location, LocationRequest request) {
        if (request.parentId() != null) {
            Location parent = findOwned(user, request.parentId());
            if (location.getId() != null && parent.getId().equals(location.getId())) {
                throw new IllegalArgumentException("Location cannot be its own parent");
            }
            location.setParentId(parent.getId());
        } else {
            location.setParentId(null);
        }
        location.setName(normalizeRequired(request.name(), "name"));
        location.setDescription(normalizeOptional(request.description()));
        location.setBuilding(normalizeOptional(request.building()));
        location.setFloor(normalizeOptional(request.floor()));
        location.setRoom(normalizeOptional(request.room()));
        location.setZone(normalizeOptional(request.zone()));
        location.setTagsJson(toTagsJson(request.tags()));
    }

    private String toTagsJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        List<String> normalized = tags.stream()
                .map(this::normalizeOptional)
                .filter(tag -> tag != null)
                .distinct()
                .toList();
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid location tags");
        }
    }

    private List<String> fromTagsJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }
}
