package com.voltcanary.alert.notification.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.alert.notifications")
public class AlertNotificationProperties {

    @Getter
    @Setter
    private boolean enabled = true;
    @Getter
    @Setter
    private boolean workerEnabled = true;
    @Getter
    private int maxAttempts = 3;
    @Getter
    private Duration retryInterval = Duration.ofSeconds(1);
    @Getter
    private double retryMultiplier = 1.0d;
    private Duration maxRetryDelay = Duration.ofSeconds(1);
    @Getter
    private double retryJitterPercent = 0.0d;
    @Getter
    private Duration leaseDuration = Duration.ofSeconds(30);
    @Getter
    private int claimBatchSize = 25;
    @Getter
    private int connectTimeoutMillis = 2000;
    @Getter
    private int readTimeoutMillis = 5000;
    @Getter
    private final Email email = new Email();
    @Getter
    private final Telegram telegram = new Telegram();

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval == null ? Duration.ZERO : retryInterval;
        if (maxRetryDelay.compareTo(this.retryInterval) < 0) {
            maxRetryDelay = this.retryInterval;
        }
    }

    public void setRetryMultiplier(double retryMultiplier) {
        this.retryMultiplier = Math.max(1.0d, retryMultiplier);
    }

    public Duration getMaxRetryDelay() {
        return maxRetryDelay.compareTo(retryInterval) < 0 ? retryInterval : maxRetryDelay;
    }

    public void setMaxRetryDelay(Duration maxRetryDelay) {
        this.maxRetryDelay = maxRetryDelay == null || maxRetryDelay.isNegative()
                ? retryInterval
                : maxRetryDelay;
    }

    public void setRetryJitterPercent(double retryJitterPercent) {
        this.retryJitterPercent = Math.max(0.0d, Math.min(100.0d, retryJitterPercent));
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                ? Duration.ofSeconds(30)
                : leaseDuration;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = Math.max(1, claimBatchSize);
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = Math.max(1, connectTimeoutMillis);
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = Math.max(1, readTimeoutMillis);
    }

    public static class Email {

        @Getter
        private String fromAddress;

        public void setFromAddress(String fromAddress) {
            this.fromAddress = fromAddress == null || fromAddress.isBlank() ? null : fromAddress.trim();
        }
    }

    public static class Telegram {

        @Getter
        private String apiBaseUrl = "https://api.telegram.org";
        @Getter
        private String botToken;

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank()
                    ? "https://api.telegram.org"
                    : apiBaseUrl.trim();
        }

        public void setBotToken(String botToken) {
            this.botToken = botToken == null || botToken.isBlank() ? null : botToken.trim();
        }
    }
}
