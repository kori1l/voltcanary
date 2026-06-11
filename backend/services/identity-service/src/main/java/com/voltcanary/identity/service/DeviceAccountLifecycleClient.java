package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityDownstreamProperties;
import com.voltcanary.identity.domain.UserStatus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DeviceAccountLifecycleClient {

    private final RestClient restClient;
    private final IdentityDownstreamProperties properties;

    public DeviceAccountLifecycleClient(
            RestClient.Builder restClientBuilder,
            IdentityDownstreamProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getDeviceServiceBaseUrl()).build();
    }

    public void sync(UUID userId, String email, UserStatus userStatus) {
        if (!properties.isDeviceLifecycleSyncEnabled()) {
            return;
        }
        try {
            restClient.post()
                    .uri("/internal/accounts/users/{userId}/lifecycle", userId)
                    .header("X-Internal-Service-Token", properties.getInternalServiceToken())
                    .body(Map.of(
                            "email", email,
                            "status", accountStatus(userStatus)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device account lifecycle sync failed", exception);
        }
    }

    public Optional<String> findAccountStatus(UUID userId) {
        try {
            AccountLifecycleStatusResponse response = restClient.get()
                    .uri("/internal/accounts/users/{userId}/lifecycle", userId)
                    .header("X-Internal-Service-Token", properties.getInternalServiceToken())
                    .retrieve()
                    .body(AccountLifecycleStatusResponse.class);
            return Optional.ofNullable(response).map(AccountLifecycleStatusResponse::status);
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Device account lifecycle lookup failed", exception);
        }
    }

    private String accountStatus(UserStatus userStatus) {
        if (userStatus == UserStatus.ACTIVE) {
            return "ACTIVE";
        }
        if (userStatus == UserStatus.DELETED) {
            return "DELETED";
        }
        return "DISABLED";
    }

    private record AccountLifecycleStatusResponse(UUID userId, String status) {
    }
}
