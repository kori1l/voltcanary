package com.voltcanary.ingestion.config;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ingestion")
public class IngestionProperties {

    private Instant minAcceptedTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    private Duration maxFutureSkew = Duration.ofSeconds(30);
    private Duration maxBackfillAge = Duration.ofDays(30);
    private int maxMetricsPerReading = 100;
    private int maxReadingsPerBatch = 500;
    private int maxSourceIdLength = 255;
    private int maxMetricNameLength = 100;
    private Duration kafkaAckTimeout = Duration.ofSeconds(3);
    private Duration idempotencyTtl = Duration.ofHours(24);
    private boolean rateLimitEnabled = true;
    private Duration rateLimitWindow = Duration.ofMinutes(1);
    private int sourceRequestsPerWindow = 600;
    private int deviceRequestsPerWindow = 1_200;
    private int accountRequestsPerWindow = 5_000;
    private int ipRequestsPerWindow = 1_000;
    private boolean backpressureEnabled = true;
    private Duration backpressureCooldown = Duration.ofSeconds(30);
    private int auditCapacity = 1_000;

    public Instant getMinAcceptedTimestamp() {
        return minAcceptedTimestamp;
    }

    public void setMinAcceptedTimestamp(Instant minAcceptedTimestamp) {
        this.minAcceptedTimestamp = minAcceptedTimestamp;
    }

    public Duration getMaxFutureSkew() {
        return maxFutureSkew;
    }

    public void setMaxFutureSkew(Duration maxFutureSkew) {
        this.maxFutureSkew = maxFutureSkew;
    }

    public Duration getMaxBackfillAge() {
        return maxBackfillAge;
    }

    public void setMaxBackfillAge(Duration maxBackfillAge) {
        this.maxBackfillAge = maxBackfillAge;
    }

    public int getMaxMetricsPerReading() {
        return maxMetricsPerReading;
    }

    public void setMaxMetricsPerReading(int maxMetricsPerReading) {
        this.maxMetricsPerReading = maxMetricsPerReading;
    }

    public int getMaxReadingsPerBatch() {
        return maxReadingsPerBatch;
    }

    public void setMaxReadingsPerBatch(int maxReadingsPerBatch) {
        this.maxReadingsPerBatch = maxReadingsPerBatch;
    }

    public int getMaxSourceIdLength() {
        return maxSourceIdLength;
    }

    public void setMaxSourceIdLength(int maxSourceIdLength) {
        this.maxSourceIdLength = maxSourceIdLength;
    }

    public int getMaxMetricNameLength() {
        return maxMetricNameLength;
    }

    public void setMaxMetricNameLength(int maxMetricNameLength) {
        this.maxMetricNameLength = maxMetricNameLength;
    }

    public Duration getKafkaAckTimeout() {
        return kafkaAckTimeout;
    }

    public void setKafkaAckTimeout(Duration kafkaAckTimeout) {
        this.kafkaAckTimeout = kafkaAckTimeout;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public void setRateLimitWindow(Duration rateLimitWindow) {
        this.rateLimitWindow = rateLimitWindow;
    }

    public int getSourceRequestsPerWindow() {
        return sourceRequestsPerWindow;
    }

    public void setSourceRequestsPerWindow(int sourceRequestsPerWindow) {
        this.sourceRequestsPerWindow = sourceRequestsPerWindow;
    }

    public int getDeviceRequestsPerWindow() {
        return deviceRequestsPerWindow;
    }

    public void setDeviceRequestsPerWindow(int deviceRequestsPerWindow) {
        this.deviceRequestsPerWindow = deviceRequestsPerWindow;
    }

    public int getAccountRequestsPerWindow() {
        return accountRequestsPerWindow;
    }

    public void setAccountRequestsPerWindow(int accountRequestsPerWindow) {
        this.accountRequestsPerWindow = accountRequestsPerWindow;
    }

    public int getIpRequestsPerWindow() {
        return ipRequestsPerWindow;
    }

    public void setIpRequestsPerWindow(int ipRequestsPerWindow) {
        this.ipRequestsPerWindow = ipRequestsPerWindow;
    }

    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    public void setBackpressureEnabled(boolean backpressureEnabled) {
        this.backpressureEnabled = backpressureEnabled;
    }

    public Duration getBackpressureCooldown() {
        return backpressureCooldown;
    }

    public void setBackpressureCooldown(Duration backpressureCooldown) {
        this.backpressureCooldown = backpressureCooldown;
    }

    public int getAuditCapacity() {
        return auditCapacity;
    }

    public void setAuditCapacity(int auditCapacity) {
        this.auditCapacity = auditCapacity;
    }
}
