package com.voltcanary.ingestion.pipeline;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.abuse.IngestionRateLimiter;
import com.voltcanary.ingestion.audit.IngestionAuditService;
import com.voltcanary.ingestion.backpressure.KafkaBackpressureService;
import com.voltcanary.ingestion.config.IngestionProperties;
import com.voltcanary.ingestion.idempotency.IdempotencyService;
import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
import com.voltcanary.ingestion.normalization.PayloadNormalizer;
import com.voltcanary.ingestion.source.ResolvedSource;
import com.voltcanary.ingestion.source.SourceResolver;
import com.voltcanary.ingestion.source.SourceUsageRecorder;
import com.voltcanary.ingestion.validation.IngestionValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultTelemetryIngestionPipeline implements TelemetryIngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DefaultTelemetryIngestionPipeline.class);

    private final IngestionValidator ingestionValidator;
    private final PayloadNormalizer payloadNormalizer;
    private final SourceResolver sourceResolver;
    private final SourceUsageRecorder sourceUsageRecorder;
    private final IdempotencyService idempotencyService;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final IngestionRateLimiter rateLimiter;
    private final KafkaBackpressureService backpressureService;
    private final IngestionAuditService auditService;
    private final MeterRegistry meterRegistry;
    private final boolean kafkaPublishEnabled;

    public DefaultTelemetryIngestionPipeline(
            IngestionValidator ingestionValidator,
            PayloadNormalizer payloadNormalizer,
            SourceResolver sourceResolver,
            SourceUsageRecorder sourceUsageRecorder,
            IdempotencyService idempotencyService,
            TelemetryEventPublisher telemetryEventPublisher,
            IngestionRateLimiter rateLimiter,
            KafkaBackpressureService backpressureService,
            IngestionAuditService auditService,
            MeterRegistry meterRegistry,
            IngestionProperties properties) {
        this.ingestionValidator = ingestionValidator;
        this.payloadNormalizer = payloadNormalizer;
        this.sourceResolver = sourceResolver;
        this.sourceUsageRecorder = sourceUsageRecorder;
        this.idempotencyService = idempotencyService;
        this.telemetryEventPublisher = telemetryEventPublisher;
        this.rateLimiter = rateLimiter;
        this.backpressureService = backpressureService;
        this.auditService = auditService;
        this.meterRegistry = meterRegistry;
        this.kafkaPublishEnabled = properties.isKafkaPublishEnabled();
    }

    @Override
    public IngestionResult ingest(IncomingTelemetryMessage message) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "accepted";
        try {
            backpressureService.check();
            ingestionValidator.validate(message);
            IncomingTelemetryMessage normalizedMessage = payloadNormalizer.normalize(message);
            ResolvedSource source = sourceResolver.resolve(normalizedMessage);
            rateLimiter.check(normalizedMessage, source);
            sourceUsageRecorder.record(source);
            if (!idempotencyService.markIfNew(
                    source.deviceId(),
                    normalizedMessage.sourceId(),
                    normalizedMessage.timestamp(),
                    normalizedMessage.metrics())) {
                outcome = "duplicate";
                meterRegistry.counter("voltcanary.ingestion.readings", "outcome", outcome).increment();
                auditService.record(
                        outcome,
                        "duplicate",
                        normalizedMessage.correlationId(),
                        source.deviceId(),
                        normalizedMessage.sourceId(),
                        null);
                log.info(
                        "Ignoring duplicate telemetry reading correlationId={} deviceId={} sourceId={} timestamp={}",
                        normalizedMessage.correlationId(),
                        source.deviceId(),
                        normalizedMessage.sourceId(),
                        normalizedMessage.timestamp());
                return new IngestionResult(false, null, "duplicate");
            }

            UUID eventId = UUID.randomUUID();
            TelemetryReceivedEvent event = new TelemetryReceivedEvent(
                    eventId,
                    source.deviceId(),
                    normalizedMessage.sourceId(),
                    normalizedMessage.timestamp(),
                    normalizedMessage.metrics(),
                    Instant.now());
            if (kafkaPublishEnabled) {
                try {
                    telemetryEventPublisher.publish(event);
                    backpressureService.markPublishSuccess();
                } catch (RuntimeException exception) {
                    idempotencyService.forget(
                            source.deviceId(),
                            normalizedMessage.sourceId(),
                            normalizedMessage.timestamp(),
                            normalizedMessage.metrics());
                    backpressureService.markPublishFailure();
                    auditService.record(
                            "failed_publish",
                            exception.getClass().getSimpleName(),
                            normalizedMessage.correlationId(),
                            source.deviceId(),
                            normalizedMessage.sourceId(),
                            eventId);
                    throw exception;
                }
            }
            meterRegistry.counter("voltcanary.ingestion.readings", "outcome", outcome).increment();
            meterRegistry.counter("voltcanary.ingestion.metrics").increment(normalizedMessage.metrics().size());
            log.info(
                    "Accepted telemetry reading correlationId={} eventId={} deviceId={} sourceId={} protocol={} metricCount={}",
                    normalizedMessage.correlationId(),
                    eventId,
                    source.deviceId(),
                    normalizedMessage.sourceId(),
                    normalizedMessage.protocol(),
                    normalizedMessage.metrics().size());
            auditService.record(
                    outcome,
                    "accepted",
                    normalizedMessage.correlationId(),
                    source.deviceId(),
                    normalizedMessage.sourceId(),
                    eventId);
            return new IngestionResult(true, eventId, "accepted");
        } catch (RuntimeException exception) {
            outcome = "rejected";
            meterRegistry.counter("voltcanary.ingestion.readings", "outcome", outcome).increment();
            auditService.record(
                    outcome,
                    exception.getClass().getSimpleName(),
                    message == null ? null : message.correlationId(),
                    message == null ? null : message.deviceId(),
                    message == null ? null : message.sourceId(),
                    null);
            throw exception;
        } finally {
            sample.stop(Timer.builder("voltcanary.ingestion.processing")
                    .description("Telemetry ingestion processing latency")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }
}
