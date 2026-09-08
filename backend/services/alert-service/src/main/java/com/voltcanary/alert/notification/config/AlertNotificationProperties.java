package com.voltcanary.alert.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.alert.notifications")
public class AlertNotificationProperties {

    private boolean enabled = true;
    private boolean workerEnabled = true;
    private int maxAttempts = 3;
    private Duration retryInterval = Duration.ofSeconds(1);
    private double retryMultiplier = 1.0d;
    private Duration maxRetryDelay = Duration.ofSeconds(1);
    private double retryJitterPercent = 0.0d;
    private Duration leaseDuration = Duration.ofSeconds(30);
    private int claimBatchSize = 25;
    private int connectTimeoutMillis = 2000;
    private int readTimeoutMillis = 5000;
    private final Email email = new Email();
    private final Telegram telegram = new Telegram();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public Duration getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval == null ? Duration.ZERO : retryInterval;
        if (maxRetryDelay.compareTo(this.retryInterval) < 0) {
            maxRetryDelay = this.retryInterval;
        }
    }

    public double getRetryMultiplier() {
        return retryMultiplier;
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

    public double getRetryJitterPercent() {
        return retryJitterPercent;
    }

    public void setRetryJitterPercent(double retryJitterPercent) {
        this.retryJitterPercent = Math.max(0.0d, Math.min(100.0d, retryJitterPercent));
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                ? Duration.ofSeconds(30)
                : leaseDuration;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = Math.max(1, claimBatchSize);
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = Math.max(1, connectTimeoutMillis);
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = Math.max(1, readTimeoutMillis);
    }

    public Email getEmail() {
        return email;
    }

    public Telegram getTelegram() {
        return telegram;
    }

    public static class Email {

        private String fromAddress;

        public String getFromAddress() {
            return fromAddress;
        }

        public void setFromAddress(String fromAddress) {
            this.fromAddress = fromAddress == null || fromAddress.isBlank() ? null : fromAddress.trim();
        }
    }

    public static class Telegram {

        private String apiBaseUrl = "https://api.telegram.org";
        private String botToken;

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank()
                    ? "https://api.telegram.org"
                    : apiBaseUrl.trim();
        }

        public String getBotToken() {
            return botToken;
        }

        public void setBotToken(String botToken) {
            this.botToken = botToken == null || botToken.isBlank() ? null : botToken.trim();
        }
    }
}
