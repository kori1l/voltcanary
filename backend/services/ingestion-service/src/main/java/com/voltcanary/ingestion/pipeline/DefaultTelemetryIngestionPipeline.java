package com.voltcanary.ingestion.pipeline;

import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.idempotency.IdempotencyService;
import com.voltcanary.ingestion.kafka.TelemetryEventPublisher;
import com.voltcanary.ingestion.normalization.PayloadNormalizer;
import com.voltcanary.ingestion.source.ResolvedSource;
import com.voltcanary.ingestion.source.SourceResolver;
import com.voltcanary.ingestion.source.SourceUsageRecorder;
import com.voltcanary.ingestion.validation.IngestionValidator;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean kafkaPublishEnabled;

    public DefaultTelemetryIngestionPipeline(
            IngestionValidator ingestionValidator,
            PayloadNormalizer payloadNormalizer,
            SourceResolver sourceResolver,
            SourceUsageRecorder sourceUsageRecorder,
            IdempotencyService idempotencyService,
            TelemetryEventPublisher telemetryEventPublisher,
            @Value("${app.kafka.publish-enabled:false}") boolean kafkaPublishEnabled) {
        this.ingestionValidator = ingestionValidator;
        this.payloadNormalizer = payloadNormalizer;
        this.sourceResolver = sourceResolver;
        this.sourceUsageRecorder = sourceUsageRecorder;
        this.idempotencyService = idempotencyService;
        this.telemetryEventPublisher = telemetryEventPublisher;
        this.kafkaPublishEnabled = kafkaPublishEnabled;
    }

    @Override
    public IngestionResult ingest(IncomingTelemetryMessage message) {
        ingestionValidator.validate(message);
        IncomingTelemetryMessage normalizedMessage = payloadNormalizer.normalize(message);
        ResolvedSource source = sourceResolver.resolve(normalizedMessage);
        sourceUsageRecorder.record(source);
        if (!idempotencyService.markIfNew(
                source.deviceId(), normalizedMessage.sourceId(), normalizedMessage.timestamp())) {
            log.info(
                    "Ignoring duplicate telemetry reading deviceId={} sourceId={} timestamp={}",
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
            telemetryEventPublisher.publish(event);
        }
        log.info(
                "Accepted telemetry reading eventId={} deviceId={} sourceId={} protocol={} metricCount={}",
                eventId,
                source.deviceId(),
                normalizedMessage.sourceId(),
                normalizedMessage.protocol(),
                normalizedMessage.metrics().size());
        return new IngestionResult(true, eventId, "accepted");
    }
}
