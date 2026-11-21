package com.voltcanary.ingestion.pipeline;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.abuse.IngestionRateLimiter;
import com.voltcanary.ingestion.audit.IngestionAuditService;
import com.voltcanary.ingestion.backpressure.KafkaBackpressureService;
import com.voltcanary.ingestion.canonical.IncomingTelemetryMessage;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DefaultTelemetryIngestionPipeline implements TelemetryIngestionPipeline {

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
    private final com.voltcanary.ingestion.source.SourceProcessingProjection processing;

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
            IngestionProperties properties,
            com.voltcanary.ingestion.source.SourceProcessingProjection processing) {
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
        this.processing = processing;
    }

    @Override
    public IngestionResult ingest(IncomingTelemetryMessage message) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "accepted";
        try {
            backpressureService.check();
            ingestionValidator.validateEnvelope(message);
            var position = message.sourcePosition();
            if (position != null && (message.historicalImport()
                    || message.sourceToken() == null || message.sourceToken().isBlank())) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Source position requires authenticated live ingestion");
            }
            ResolvedSource source = sourceResolver.resolve(message);
            var bindingSchemaPin = com.voltcanary.ingestion.source.SourceBindingPins.pin(source);
            if (position != null && bindingSchemaPin == null) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Source position requires a live bound source");
            }
            var processingContext = processing.pin(source);
            if (processingContext != null && message.historicalImport()) {
                processingContext = new com.voltcanary.common.events.telemetry.ProcessingContext(
                        processingContext.dataSourceId(), processingContext.generation(), processingContext.digest(), "v1", true);
            }
            IncomingTelemetryMessage normalizedMessage = payloadNormalizer.normalize(message, source.mapping());
            if (processingContext != null && normalizedMessage.metrics().isEmpty()) {
                normalizedMessage = new IncomingTelemetryMessage(normalizedMessage.sourceId(), normalizedMessage.deviceId(),
                        normalizedMessage.accountId(), normalizedMessage.occurredAt(), normalizedMessage.receivedAt(),
                        java.util.Map.of("configuredFields", java.util.Map.of()), normalizedMessage.origin(),
                        normalizedMessage.context(), normalizedMessage.historicalImport(), position);
            }
            processing.validateInputs(source, normalizedMessage.metrics(), processingContext);
            rateLimiter.check(normalizedMessage, source);
            if (normalizedMessage.metrics().isEmpty()) {
                outcome = "filtered";
                sourceUsageRecorder.record(source);
                meterRegistry.counter("voltcanary.ingestion.readings", "outcome", outcome).increment();
                auditService.record(outcome, "source_policy", message.correlationId(), source.deviceId(), message.sourceId(), null);
                return new IngestionResult(true, null, "filtered");
            }
            ingestionValidator.validate(normalizedMessage, processingContext != null);
            sourceUsageRecorder.record(source);
            var deduplicationInput = position == null ? normalizedMessage.metrics() : java.util.Map.<String, Object>of(
                    "sourcePosition", position.epoch() + ":" + position.sequence(),
                    "schemaSnapshot", bindingSchemaPin.snapshotId().toString(), "metrics", normalizedMessage.metrics());
            if (!idempotencyService.markIfNew(
                    source.deviceId(),
                    normalizedMessage.sourceId(),
                    normalizedMessage.occurredAt(),
                    deduplicationInput)) {
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
                        normalizedMessage.occurredAt());
                return new IngestionResult(false, null, "duplicate");
            }

            UUID eventId = UUID.randomUUID();
            Instant receivedAt = normalizedMessage.receivedAt();
            TelemetryReceivedEvent event = new TelemetryReceivedEvent(
                    eventId,
                    source.deviceId(),
                    normalizedMessage.sourceId(),
                    normalizedMessage.occurredAt(),
                    normalizedMessage.metrics(),
                    receivedAt,
                    "v1",
                    normalizedMessage.correlationId(),
                    "ingestion-service",
                    source.accountId(),
                    normalizedMessage.occurredAt(), processingContext, bindingSchemaPin, position);
            if (kafkaPublishEnabled) {
                try {
                    telemetryEventPublisher.publish(event);
                    backpressureService.markPublishSuccess();
                } catch (RuntimeException exception) {
                    idempotencyService.forget(
                            source.deviceId(),
                            normalizedMessage.sourceId(),
                            normalizedMessage.occurredAt(),
                            deduplicationInput);
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
                    normalizedMessage.origin().protocol(),
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
