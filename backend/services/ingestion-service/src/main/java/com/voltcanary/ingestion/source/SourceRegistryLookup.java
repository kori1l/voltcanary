package com.voltcanary.ingestion.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated cache-miss recovery; never forward the device's raw source token. */
public class SourceRegistryLookup {
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String serviceToken;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SourceRegistryLookup(ObjectMapper mapper, String baseUrl, String serviceToken) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.serviceToken = serviceToken;
    }

    public Optional<InternalDataSourceResponse> tokenPrefix(String prefix) {
        return read("by-token-prefix/", prefix);
    }

    public Optional<InternalDataSourceResponse> externalId(String id) {
        return read("by-external-id/", id);
    }

    private Optional<InternalDataSourceResponse> read(String route, String value) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/data-sources/" + route + URLEncoder.encode(value, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(3)).header("X-Internal-Service-Token", serviceToken).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200 || response.body().length() > 65536) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Source registry unavailable");
            return Optional.of(mapper.readValue(response.body(), InternalDataSourceResponse.class));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Source registry unavailable");
        } catch (java.io.IOException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Source registry unavailable");
        }
    }
}
