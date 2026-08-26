package com.voltcanary.ingestion.source;

import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.device.DeviceValidationClient;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SourceResolver {

    private final SourceRegistryCache cache;
    private final SourceTokenValidator tokenValidator;
    private final DeviceServiceSourceClient sourceClient;
    private final DeviceValidationClient deviceValidationClient;

    public SourceResolver(
            SourceRegistryCache cache,
            SourceTokenValidator tokenValidator,
            DeviceServiceSourceClient sourceClient,
            DeviceValidationClient deviceValidationClient) {
        this.cache = cache;
        this.tokenValidator = tokenValidator;
        this.sourceClient = sourceClient;
        this.deviceValidationClient = deviceValidationClient;
    }

    public ResolvedSource resolve(IncomingTelemetryMessage message) {
        if (message.sourceToken() == null || message.sourceToken().isBlank()) {
            if (message.protocol() == ProtocolType.MQTT) {
                return resolveMqtt(message);
            }
            return resolveDevMode(message);
        }

        String tokenPrefix = tokenValidator.prefix(message.sourceToken());
        InternalDataSourceResponse source = cache.getByTokenPrefix(tokenPrefix)
                .orElseGet(() -> {
                    InternalDataSourceResponse fetched = fetchByTokenPrefix(tokenPrefix);
                    cache.putByTokenPrefix(tokenPrefix, fetched);
                    return fetched;
                });
        if (!tokenValidator.matches(message.sourceToken(), source.tokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid source token");
        }
        if (!"ACTIVE".equalsIgnoreCase(source.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Data source is not active");
        }
        if (message.sourceId() != null && !message.sourceId().equals(source.externalId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source token does not belong to submitted sourceId");
        }
        if (message.deviceId() != null && !message.deviceId().equals(source.deviceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source token does not belong to submitted deviceId");
        }
        if (message.protocol() == ProtocolType.REST && !"REST".equalsIgnoreCase(source.protocol())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data source protocol does not allow REST ingestion");
        }
        if (!deviceValidationClient.dataSourceExists(source.deviceId(), source.externalId())) {
            cache.evictByTokenPrefix(tokenPrefix);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown device data source");
        }
        return new ResolvedSource(
                source.accountId(),
                source.deviceId(),
                source.dataSourceId(),
                source.externalId(),
                source.sourceType(),
                source.protocol(),
                source.status());
    }

    private InternalDataSourceResponse fetchByTokenPrefix(String tokenPrefix) {
        try {
            return sourceClient.findByTokenPrefix(tokenPrefix);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid source token", exception);
        }
    }

    private ResolvedSource resolveDevMode(IncomingTelemetryMessage message) {
        UUID deviceId = message.deviceId();
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Source token is required");
        }
        if (!deviceValidationClient.dataSourceExists(deviceId, message.sourceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown device data source");
        }
        return new ResolvedSource(null, deviceId, null, message.sourceId(), "DEV", "REST", "ACTIVE");
    }

    private ResolvedSource resolveMqtt(IncomingTelemetryMessage message) {
        InternalDataSourceResponse source = sourceClient.findByExternalId(message.sourceId());
        if (!"ACTIVE".equalsIgnoreCase(source.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Data source is not active");
        }
        if (!"MQTT".equalsIgnoreCase(source.protocol())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data source protocol does not allow MQTT ingestion");
        }
        return new ResolvedSource(
                source.accountId(),
                source.deviceId(),
                source.dataSourceId(),
                source.externalId(),
                source.sourceType(),
                source.protocol(),
                source.status());
    }
}
