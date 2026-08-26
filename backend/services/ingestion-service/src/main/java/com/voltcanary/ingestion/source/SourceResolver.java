package com.voltcanary.ingestion.source;

import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SourceResolver {

    private final SourceRegistryCache cache;
    private final SourceTokenValidator tokenValidator;

    public SourceResolver(
            SourceRegistryCache cache,
            SourceTokenValidator tokenValidator) {
        this.cache = cache;
        this.tokenValidator = tokenValidator;
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid source token"));
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
        return resolved(source);
    }

    private ResolvedSource resolveDevMode(IncomingTelemetryMessage message) {
        UUID deviceId = message.deviceId();
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Source token is required");
        }
        InternalDataSourceResponse source = cache.getByExternalId(message.sourceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown device data source"));
        if (!deviceId.equals(source.deviceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown device data source");
        }
        if (!"ACTIVE".equalsIgnoreCase(source.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Data source is not active");
        }
        return resolved(source);
    }

    private ResolvedSource resolveMqtt(IncomingTelemetryMessage message) {
        InternalDataSourceResponse source = cache.getByExternalId(message.sourceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown device data source"));
        if (!"ACTIVE".equalsIgnoreCase(source.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Data source is not active");
        }
        if (!"MQTT".equalsIgnoreCase(source.protocol())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data source protocol does not allow MQTT ingestion");
        }
        return resolved(source);
    }

    private ResolvedSource resolved(InternalDataSourceResponse source) {
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
