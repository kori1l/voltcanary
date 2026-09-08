package com.voltcanary.alert.notification.provider;

import com.voltcanary.alert.notification.config.AlertNotificationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class WebhookNotificationProviderAdapter implements NotificationProviderAdapter {

    private final RestClient restClient;

    public WebhookNotificationProviderAdapter(
            AlertNotificationProperties properties,
            RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .requestFactory(requestFactory(properties))
                .build();
    }

    @Override
    public String channelType() {
        return "WEBHOOK";
    }

    @Override
    public NotificationDeliveryResult deliver(NotificationDeliveryCommand command) {
        String url = deliveryUrl(command.channelConfig());
        if (url == null) {
            return NotificationDeliveryResult.permanentFailure(
                    "CONFIG_MISSING_URL",
                    command.channelType() + " channel requires config.url");
        }
        try {
            HttpStatusCode status = restClient.post()
                    .uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.idempotencyKey())
                    .body(AlertNotificationMessage.from(command).payload())
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return NotificationDeliveryResult.sent("HTTP " + status.value(), null);
        } catch (RestClientResponseException exception) {
            String errorCode = "HTTP_" + exception.getStatusCode().value();
            if (exception.getStatusCode().is5xxServerError()) {
                return NotificationDeliveryResult.retryableFailure(errorCode, responseSummary(exception));
            }
            return NotificationDeliveryResult.permanentFailure(errorCode, responseSummary(exception));
        } catch (Exception exception) {
            return NotificationDeliveryResult.retryableFailure(
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
        }
    }

    private String deliveryUrl(Map<String, Object> config) {
        Object url = config == null ? null : config.get("url");
        if (url instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return null;
    }

    private String responseSummary(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "HTTP " + exception.getStatusCode().value();
        }
        return "HTTP " + exception.getStatusCode().value() + ": " + body;
    }

    private SimpleClientHttpRequestFactory requestFactory(AlertNotificationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        return factory;
    }
}
