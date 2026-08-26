package com.voltcanary.ingestion.adapter.http;

import com.voltcanary.ingestion.adapter.api.AdapterCapabilities;
import com.voltcanary.ingestion.adapter.api.AdapterCapability;
import com.voltcanary.ingestion.adapter.api.AdapterManifest;
import com.voltcanary.ingestion.adapter.api.AdapterType;
import com.voltcanary.ingestion.adapter.api.PollingTelemetryAdapter;
import com.voltcanary.ingestion.adapter.api.ProtocolType;
import com.voltcanary.ingestion.adapter.api.SourceRuntimeConfig;
import com.voltcanary.ingestion.adapter.api.TransportType;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
import com.voltcanary.ingestion.canonical.TelemetryOrigin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HttpPollingTelemetryAdapter implements PollingTelemetryAdapter {

    private static final AdapterManifest MANIFEST = new AdapterManifest(
            "generic-http-polling",
            AdapterType.GENERIC_HTTP_POLLING,
            "Generic HTTP Polling",
            TransportType.HTTP,
            ProtocolType.HTTP,
            AdapterCapabilities.of(AdapterCapability.POLLING),
            "v1");

    private final HttpClient httpClient;
    private final CredentialProvider credentialProvider;
    private final HttpPollingResponseMapper responseMapper;

    @Autowired
    public HttpPollingTelemetryAdapter(CredentialProvider credentialProvider, HttpPollingResponseMapper responseMapper) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), credentialProvider, responseMapper);
    }

    HttpPollingTelemetryAdapter(
            HttpClient httpClient,
            CredentialProvider credentialProvider,
            HttpPollingResponseMapper responseMapper) {
        this.httpClient = httpClient;
        this.credentialProvider = credentialProvider;
        this.responseMapper = responseMapper;
    }

    @Override
    public AdapterManifest manifest() {
        return MANIFEST;
    }

    @Override
    public List<IncomingTelemetryMessage> poll(SourceRuntimeConfig sourceRuntimeConfig) {
        HttpPollingAdapterConfig config = HttpPollingAdapterConfig.from(sourceRuntimeConfig);
        HttpRequest request = request(sourceRuntimeConfig, config);
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP polling endpoint returned status " + response.statusCode());
            }
            return List.of(responseMapper.map(
                    sourceRuntimeConfig,
                    response.body(),
                    response.statusCode(),
                    config.endpoint(),
                    origin(config.endpoint())));
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP polling request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP polling request interrupted", exception);
        }
    }

    private HttpRequest request(SourceRuntimeConfig sourceRuntimeConfig, HttpPollingAdapterConfig config) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.endpoint())
                .timeout(config.timeout());
        if ("POST".equals(config.method())) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        config.headers().forEach(builder::header);
        for (Map.Entry<String, String> entry : config.credentialHeaders().entrySet()) {
            String credentialName = entry.getValue();
            String credentialReference = sourceRuntimeConfig.credentialReferences().get(credentialName);
            if (credentialReference == null || credentialReference.isBlank()) {
                throw new IllegalArgumentException("Missing credential reference " + credentialName);
            }
            String credential = credentialProvider.resolve(credentialReference)
                    .orElseThrow(() -> new IllegalArgumentException("Unable to resolve credential reference " + credentialName));
            builder.header(entry.getKey(), credential);
        }
        return builder.build();
    }

    private TelemetryOrigin origin(URI endpoint) {
        return new TelemetryOrigin(
                MANIFEST.adapterType(),
                MANIFEST.protocol(),
                MANIFEST.transport(),
                MANIFEST.adapterId(),
                Map.of("endpointHost", endpoint.getHost() == null ? "" : endpoint.getHost()));
    }
}
