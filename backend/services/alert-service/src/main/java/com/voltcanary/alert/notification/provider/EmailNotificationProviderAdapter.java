package com.voltcanary.alert.notification.provider;

import com.voltcanary.alert.notification.config.AlertNotificationProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EmailNotificationProviderAdapter implements NotificationProviderAdapter {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_ADDRESS_COUNT = 50;
    private static final int MAX_ALIAS_LENGTH = 120;
    private static final int MAX_SUMMARY_LENGTH = 500;

    private final JavaMailSender mailSender;
    private final AlertNotificationProperties properties;

    @Autowired
    public EmailNotificationProviderAdapter(
            ObjectProvider<JavaMailSender> mailSender,
            AlertNotificationProperties properties) {
        this(mailSender.getIfAvailable(), properties);
    }

    EmailNotificationProviderAdapter(
            JavaMailSender mailSender,
            AlertNotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public String channelType() {
        return "EMAIL";
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        addresses(config, "recipients", true);
        addresses(config, "cc", false);
        addresses(config, "bcc", false);
        boundedString(config, "fromAlias", MAX_ALIAS_LENGTH);
        boundedString(config, "subjectPrefix", MAX_ALIAS_LENGTH);
    }

    @Override
    public NotificationDeliveryResult deliver(NotificationDeliveryCommand command) {
        if (mailSender == null) {
            return NotificationDeliveryResult.permanentFailure(
                    "EMAIL_PROVIDER_UNCONFIGURED",
                    "Email provider is not configured");
        }
        try {
            mailSender.send(message(command));
            return NotificationDeliveryResult.sent("EMAIL accepted", null);
        } catch (ResponseStatusException exception) {
            return NotificationDeliveryResult.permanentFailure(
                    "INVALID_EMAIL_CONFIG",
                    exception.getReason());
        } catch (MailParseException | MailAuthenticationException | IllegalArgumentException exception) {
            return NotificationDeliveryResult.permanentFailure(
                    exception.getClass().getSimpleName(),
                    bounded(exception.getMessage()));
        } catch (MailException exception) {
            return NotificationDeliveryResult.retryableFailure(
                    exception.getClass().getSimpleName(),
                    bounded(exception.getMessage()));
        } catch (MessagingException | UnsupportedEncodingException exception) {
            return NotificationDeliveryResult.permanentFailure(
                    exception.getClass().getSimpleName(),
                    bounded(exception.getMessage()));
        }
    }

    private MimeMessage message(NotificationDeliveryCommand command)
            throws MessagingException, UnsupportedEncodingException {
        Map<String, Object> config = command.channelConfig();
        AlertNotificationMessage notification = AlertNotificationMessage.from(command);
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());
        String fromAddress = properties.getEmail().getFromAddress();
        String fromAlias = stringValue(config.get("fromAlias"));
        if (fromAddress != null && fromAlias != null) {
            helper.setFrom(fromAddress, fromAlias);
        } else if (fromAddress != null) {
            helper.setFrom(fromAddress);
        }
        helper.setTo(addresses(config, "recipients", true).toArray(String[]::new));
        List<String> cc = addresses(config, "cc", false);
        if (!cc.isEmpty()) {
            helper.setCc(cc.toArray(String[]::new));
        }
        List<String> bcc = addresses(config, "bcc", false);
        if (!bcc.isEmpty()) {
            helper.setBcc(bcc.toArray(String[]::new));
        }
        helper.setSubject(subject(config, notification.subject()));
        helper.setText(notification.text(), false);
        return mimeMessage;
    }

    private String subject(Map<String, Object> config, String canonicalSubject) {
        String prefix = stringValue(config.get("subjectPrefix"));
        return prefix == null ? canonicalSubject : prefix + " " + canonicalSubject;
    }

    private List<String> addresses(Map<String, Object> config, String field, boolean required) {
        Object value = config == null ? null : config.get(field);
        List<String> addresses = switch (value) {
            case null -> List.of();
            case String text -> splitAddresses(text);
            case Collection<?> collection -> collection.stream()
                    .map(this::stringValue)
                    .filter(item -> item != null)
                    .toList();
            default -> throw badRequest(field + " must be a list of email addresses");
        };
        if (required && addresses.isEmpty()) {
            throw badRequest(field + " is required");
        }
        if (addresses.size() > MAX_ADDRESS_COUNT) {
            throw badRequest(field + " has too many addresses");
        }
        addresses.forEach(address -> {
            if (!EMAIL_PATTERN.matcher(address).matches()) {
                throw badRequest(field + " contains an invalid email address");
            }
        });
        return addresses;
    }

    private List<String> splitAddresses(String text) {
        return Pattern.compile("[,\\n;]")
                .splitAsStream(text)
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private void boundedString(Map<String, Object> config, String field, int maxLength) {
        Object value = config == null ? null : config.get(field);
        if (value == null) {
            return;
        }
        String text = stringValue(value);
        if (text == null) {
            throw badRequest(field + " must be a string");
        }
        if (text.length() > maxLength) {
            throw badRequest(field + " is too long");
        }
    }

    private String stringValue(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "Email provider failed";
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_SUMMARY_LENGTH ? trimmed : trimmed.substring(0, MAX_SUMMARY_LENGTH);
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
