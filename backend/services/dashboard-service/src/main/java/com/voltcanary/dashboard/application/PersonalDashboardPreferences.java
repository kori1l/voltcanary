package com.voltcanary.dashboard.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardDefinition;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Preferences;
import com.voltcanary.dashboard.port.DashboardStorage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonalDashboardPreferences {
    private static final UUID EMPTY_ETAG = new UUID(0, 0);
    private final DashboardStorage storage;
    private final DashboardAccess access;
    private final DefinitionValidation validation;
    private final DefinitionBindings bindings;
    private final ObjectMapper mapper;

    public PersonalDashboardPreferences(DashboardStorage storage, DashboardAccess access,
            DefinitionValidation validation, DefinitionBindings bindings, ObjectMapper mapper) {
        this.storage = storage;
        this.access = access;
        this.validation = validation;
        this.bindings = bindings;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Preferences get(DashboardActor actor) {
        Preferences stored = storage.preferences(actor).orElseGet(() ->
                new Preferences(null, List.of(), mapper.createObjectNode(), EMPTY_ETAG, null, "NO_DEFAULT"));
        UUID defaultId = stored.defaultDashboardId();
        boolean visible = defaultId == null || selectable(actor, defaultId);
        List<UUID> favorites = stored.favorites().stream().filter(id -> selectable(actor, id)).toList();
        return new Preferences(visible ? defaultId : null, favorites, stored.defaultContext(), stored.etag(),
                stored.updatedAt(), visible ? stored.fallbackReason() : "DEFAULT_UNAVAILABLE");
    }

    @Transactional
    public Preferences save(DashboardActor actor, String authorization, String ifMatch, UUID defaultId,
            List<UUID> favorites, JsonNode context) {
        storage.lockActor(actor);
        access.match(ifMatch, get(actor).etag());
        if (favorites == null || favorites.size() > 10 || favorites.stream().distinct().count() != favorites.size()
                || favorites.stream().anyMatch(id -> !selectable(actor, id))
                || (defaultId != null && !selectable(actor, defaultId))) {
            throw new DashboardFailure("VALIDATION_ERROR", "Select accessible published dashboards", "defaultDashboardId");
        }
        DashboardDefinition contextOnly = new DashboardDefinition(1, "Preferences", "", context,
                List.of(), List.of(), mapper.createObjectNode());
        validation.validate(contextOnly);
        bindings.requireAuthorized(authorization, actor, contextOnly);
        Preferences saved = new Preferences(defaultId, List.copyOf(favorites), context,
                UUID.randomUUID(), Instant.now(), defaultId == null ? "NO_DEFAULT" : null);
        storage.savePreferences(actor, saved);
        return saved;
    }

    public void archived(DashboardActor actor, UUID id, Instant now) {
        storage.preferences(actor).ifPresent(stored -> {
            boolean wasDefault = id.equals(stored.defaultDashboardId());
            storage.savePreferences(actor, new Preferences(wasDefault ? null : stored.defaultDashboardId(),
                    stored.favorites().stream().filter(favorite -> !favorite.equals(id)).toList(),
                    stored.defaultContext(), UUID.randomUUID(), now,
                    wasDefault ? "DEFAULT_ARCHIVED" : stored.fallbackReason()));
        });
    }

    private boolean selectable(DashboardActor actor, UUID id) {
        return storage.findOwned(actor, id).filter(dashboard -> "ACTIVE".equals(dashboard.status())
                && dashboard.publishedRevision() > 0).isPresent();
    }
}
