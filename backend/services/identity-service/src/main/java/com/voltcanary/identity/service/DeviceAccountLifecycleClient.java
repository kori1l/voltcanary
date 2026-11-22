package com.voltcanary.identity.service;

import com.voltcanary.identity.config.IdentityDownstreamProperties;
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
        IdentityDownstreamProperties properties
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getDeviceServiceBaseUrl()).build();
    }

    public Optional<String> findAccountStatus(UUID userId) {
        try {
            AccountLifecycleStatusResponse response = restClient
                .get()
                .uri("/internal/accounts/users/{userId}/lifecycle", userId)
                .header("X-Internal-Service-Token", properties.getInternalServiceToken())
                .retrieve()
                .body(AccountLifecycleStatusResponse.class);
            return Optional.ofNullable(response).map(AccountLifecycleStatusResponse::status);
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Device account lifecycle lookup failed",
                exception
            );
        }
    }

    private record AccountLifecycleStatusResponse(UUID userId, String status) {}
}
