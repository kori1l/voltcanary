package com.voltcanary.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.dashboard.application.DefinitionValidation;
import com.voltcanary.dashboard.application.PersonalDashboardPreferences;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.domain.DashboardRecords.Preferences;
import com.voltcanary.dashboard.port.DashboardOwnership;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard-preferences/me")
public class DashboardPreferencesController {
    private final DashboardOwnership ownership;
    private final PersonalDashboardPreferences preferences;
    private final DefinitionValidation validation;
    private final DashboardRequestDecoder decoder;

    public DashboardPreferencesController(DashboardOwnership ownership, PersonalDashboardPreferences preferences,
            DefinitionValidation validation, DashboardRequestDecoder decoder) {
        this.ownership = ownership;
        this.preferences = preferences;
        this.validation = validation;
        this.decoder = decoder;
    }

    @GetMapping
    public ResponseEntity<Preferences> get(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Preferences current = preferences.get(ownership.resolve(authorization));
        return ResponseEntity.ok().eTag(current.etag().toString()).body(current);
    }

    @PutMapping
    public ResponseEntity<Preferences> put(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody JsonNode body) {
        var actor = ownership.resolve(authorization);
        validation.validatePayload(body);
        JsonNode favorites = body.path("favorites");
        if (!favorites.isArray() || favorites.size() > 10) {
            throw new DashboardFailure("VALIDATION_ERROR", "Expected at most ten favorites", "favorites");
        }
        var favoriteIds = new ArrayList<UUID>();
        for (JsonNode favorite : favorites) {
            try {
                if (!favorite.isTextual() || favorite.asText().length() != 36) {
                    throw new IllegalArgumentException();
                }
                favoriteIds.add(UUID.fromString(favorite.asText()));
            } catch (IllegalArgumentException exception) {
                throw new DashboardFailure("VALIDATION_ERROR", "Expected a favorite UUID", "favorites");
            }
        }
        Preferences saved = preferences.save(actor, authorization, ifMatch,
                decoder.uuid(body, "defaultDashboardId", true), favoriteIds, body.path("defaultContext"));
        return ResponseEntity.ok().eTag(saved.etag().toString()).body(saved);
    }
}
