package com.voltcanary.dashboard.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.security.JwtAccessTokenClaims;
import com.voltcanary.common.security.JwtAccessTokenService;
import com.voltcanary.dashboard.config.DashboardHttpProperties;
import com.voltcanary.dashboard.config.DashboardJwtProperties;
import com.voltcanary.dashboard.domain.DashboardActor;
import com.voltcanary.dashboard.domain.DashboardFailure;
import com.voltcanary.dashboard.port.DashboardOwnership;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class HttpDashboardOwnership implements DashboardOwnership {
    private final DashboardHttpProperties properties;
    private final JwtAccessTokenService jwt;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final com.voltcanary.dashboard.port.DashboardEvidenceReads evidenceReads;

    public HttpDashboardOwnership(DashboardHttpProperties properties, DashboardJwtProperties jwtProperties,
            ObjectMapper mapper) {
        this(properties, jwtProperties, mapper, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public HttpDashboardOwnership(DashboardHttpProperties properties, DashboardJwtProperties jwtProperties,
            ObjectMapper mapper, com.voltcanary.dashboard.port.DashboardEvidenceReads evidenceReads) {
        this.properties = properties;
        this.jwt = new JwtAccessTokenService(jwtProperties.keyId(), jwtProperties.publicKeys(),
                jwtProperties.issuer(), jwtProperties.audience());
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(properties.ownershipTimeout()).build();
        this.evidenceReads = evidenceReads;
    }

    @Override
    public DashboardActor resolveReadOnly(String authorization) {
        UUID userId = authenticatedId(authorization);
        JsonNode identity = read(properties.identityBaseUrl(), "/auth/me", authorization, false);
        if (!userId.toString().equals(identity.path("id").asText()) || !"ACTIVE".equals(identity.path("status").asText()))
            throw new DashboardFailure("ACTOR_INACTIVE", "Active user is required");
        if (evidenceReads == null) throw unavailable();
        return evidenceReads.activeActor(authorization, userId);
    }

    @Override
    public DashboardActor resolve(String authorization) {
        UUID userId = authenticatedId(authorization);
        JsonNode identity = read(properties.identityBaseUrl(), "/auth/me", authorization, false);
        if (!userId.toString().equals(identity.path("id").asText())
                || !"ACTIVE".equals(identity.path("status").asText())) {
            throw new DashboardFailure("ACTOR_INACTIVE", "Active user is required");
        }
        JsonNode account = read(properties.deviceBaseUrl(), "/accounts/current", authorization, false);
        if (!userId.toString().equals(account.path("userId").asText())
                || !"ACTIVE".equals(account.path("status").asText())) {
            throw new DashboardFailure("ACTOR_INACTIVE", "Active device account is required");
        }
        try {
            return new DashboardActor(userId, UUID.fromString(account.path("id").asText()));
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    @Override
    public boolean canReadDevice(String authorization, UUID deviceId, DashboardActor actor) {
        return resource(authorization, "/devices/" + deviceId, deviceId);
    }

    @Override
    public boolean canReadLocation(String authorization, UUID locationId, DashboardActor actor) {
        return resource(authorization, "/locations/" + locationId, locationId);
    }

    private boolean resource(String authorization, String path, UUID id) {
        JsonNode result = read(properties.deviceBaseUrl(), path, authorization, true);
        return result != null && id.toString().equals(result.path("id").asText());
    }

    private UUID authenticatedId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new DashboardFailure("UNAUTHENTICATED", "Authentication is required");
        }
        try {
            JwtAccessTokenClaims claims = jwt.validate(authorization.substring(7));
            return UUID.fromString(claims.subject());
        } catch (IllegalArgumentException exception) {
            throw new DashboardFailure("UNAUTHENTICATED", "Invalid access token");
        }
    }

    private JsonNode read(URI base, String path, String authorization, boolean resource) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(properties.ownershipTimeout())
                .header("Authorization", authorization).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw new DashboardFailure("UNAUTHENTICATED", "Authentication is required");
            }
            if (resource && (response.statusCode() == 403 || response.statusCode() == 404)) {
                return null;
            }
            if (response.statusCode() == 403) {
                throw new DashboardFailure("ACTOR_INACTIVE", "Active ownership is required");
            }
            if (response.statusCode() != 200) {
                throw unavailable();
            }
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw unavailable();
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private DashboardFailure unavailable() {
        return new DashboardFailure("OWNERSHIP_UNAVAILABLE", "Ownership service is unavailable; retry later");
    }
}
