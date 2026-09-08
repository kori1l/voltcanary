package com.voltcanary.alert.notification.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.alert.notification.config.AlertNotificationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TelegramNotificationProviderAdapter implements NotificationProviderAdapter {

    private static final Set<String> PARSE_MODES = Set.of("Markdown", "MarkdownV2", "HTML");
    private static final int MAX_CHAT_ID_LENGTH = 128;
    private static final int MAX_SUMMARY_LENGTH = 500;

    private final AlertNotificationProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TelegramNotificationProviderAdapter(
            AlertNotificationProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .requestFactory(requestFactory(properties))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String channelType() {
        return "TELEGRAM";
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        chatId(config);
        parseMode(config);
        previewFlag(config);
    }

    @Override
    public NotificationDeliveryResult deliver(NotificationDeliveryCommand command) {
        String token = properties.getTelegram().getBotToken();
        if (token == null) {
            return NotificationDeliveryResult.permanentFailure(
                    "TELEGRAM_TOKEN_UNCONFIGURED",
                    "Telegram bot token is not configured");
        }
        try {
            String body = restClient.post()
                    .uri(sendMessageUri(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.idempotencyKey())
                    .body(deliveryPayload(command))
                    .retrieve()
                    .body(String.class);
            return NotificationDeliveryResult.sent("TELEGRAM accepted", providerMessageId(body));
        } catch (RestClientResponseException exception) {
            String errorCode = "HTTP_" + exception.getStatusCode().value();
            if (isRetryable(exception.getStatusCode())) {
                return NotificationDeliveryResult.retryableFailure(errorCode, responseSummary(exception));
            }
            return NotificationDeliveryResult.permanentFailure(errorCode, responseSummary(exception));
        } catch (Exception exception) {
            return NotificationDeliveryResult.retryableFailure(
                    exception.getClass().getSimpleName(),
                    bounded(exception.getMessage()));
        }
    }

    private URI sendMessageUri(String token) {
        String baseUrl = properties.getTelegram().getApiBaseUrl();
        String trimmedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(trimmedBase + "/bot" + token + "/sendMessage");
    }

    private Map<String, Object> deliveryPayload(NotificationDeliveryCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> config = command.channelConfig();
        String parseMode = parseMode(config);
        payload.put("chat_id", chatId(config));
        payload.put("text", telegramText(AlertNotificationMessage.from(command), parseMode));
        if (parseMode != null) {
            payload.put("parse_mode", parseMode);
        }
        Object disablePreview = config.get("disableWebPagePreview");
        if (disablePreview instanceof Boolean booleanValue) {
            payload.put("disable_web_page_preview", booleanValue);
        }
        return payload;
    }

    private String telegramText(AlertNotificationMessage message, String parseMode) {
        return switch (parseMode == null ? "" : parseMode) {
            case "MarkdownV2" -> escapeMarkdownV2(message.text());
            case "HTML" -> escapeHtml(message.text());
            default -> message.text();
        };
    }

    private String chatId(Map<String, Object> config) {
        Object value = config == null ? null : config.get("chatId");
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= MAX_CHAT_ID_LENGTH) {
                return trimmed;
            }
        }
        throw badRequest("chatId is required");
    }

    private String parseMode(Map<String, Object> config) {
        Object value = config == null ? null : config.get("parseMode");
        if (value == null || (value instanceof String text && text.isBlank())) {
            return null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            return PARSE_MODES.stream()
                    .filter(mode -> mode.equalsIgnoreCase(trimmed))
                    .findFirst()
                    .orElseThrow(() -> badRequest("parseMode is not supported"));
        }
        throw badRequest("parseMode must be a string");
    }

    private void previewFlag(Map<String, Object> config) {
        Object value = config == null ? null : config.get("disableWebPagePreview");
        if (value != null && !(value instanceof Boolean)) {
            throw badRequest("disableWebPagePreview must be a boolean");
        }
    }

    private boolean isRetryable(HttpStatusCode statusCode) {
        return statusCode.value() == 429 || statusCode.is5xxServerError();
    }

    private String providerMessageId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode messageId = root.path("result").path("message_id");
            return messageId.isMissingNode() || messageId.isNull() ? null : messageId.asText();
        } catch (Exception exception) {
            return null;
        }
    }

    private String responseSummary(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "HTTP " + exception.getStatusCode().value();
        }
        return bounded("HTTP " + exception.getStatusCode().value() + ": " + body);
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "Telegram provider failed";
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_SUMMARY_LENGTH ? trimmed : trimmed.substring(0, MAX_SUMMARY_LENGTH);
    }

    private String escapeMarkdownV2(String value) {
        return value.replaceAll("([_\\\\*\\[\\]()~`>#+\\-=|{}.!])", "\\\\$1");
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private SimpleClientHttpRequestFactory requestFactory(AlertNotificationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        return factory;
    }
}
