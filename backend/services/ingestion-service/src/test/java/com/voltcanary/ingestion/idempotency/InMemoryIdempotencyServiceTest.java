package com.voltcanary.ingestion.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.common.events.telemetry.TelemetryReceivedEvent;
import com.voltcanary.ingestion.config.IngestionProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyServiceTest {

    private final MutableClock clock = new MutableClock();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final LocalDeliveryReservations service = new LocalDeliveryReservations(
        mapper,
        new IngestionProperties(),
        clock
    );
    private final UUID device = UUID.randomUUID();

    @Test
    void crashBeforePublishRetainsTheEnvelopeAndBecomesRetryableAfterLeaseExpiry() {
        var first = service.reserve(event(Map.of("power", 1)));
        assertThat(service.reserve(event(Map.of("power", 1))).status()).isEqualTo(
            IdempotencyService.Status.IN_FLIGHT
        );
        clock.advance(Duration.ofSeconds(30));
        var retry = service.reserve(event(Map.of("power", 1)));
        assertThat(retry.status()).isEqualTo(IdempotencyService.Status.ACQUIRED);
        assertThat(retry.event()).isEqualTo(first.event());
        assertThatThrownBy(() -> service.complete(first)).isInstanceOf(IllegalStateException.class);
        service.release(first);
        assertThat(service.reserve(event(Map.of("power", 1))).status()).isEqualTo(
            IdempotencyService.Status.IN_FLIGHT
        );
        service.complete(retry);
        assertThat(service.reserve(event(Map.of("power", 1))).status()).isEqualTo(
            IdempotencyService.Status.PUBLISHED
        );
    }

    @Test
    void uncertainAcknowledgementRetriesTheExactEnvelopeInsteadOfIssuingANewEvent() {
        var first = service.reserve(event(Map.of("power", 1)));
        service.release(first);
        var retry = service.reserve(event(Map.of("power", 1)));
        assertThat(retry.event()).isEqualTo(first.event());
        service.complete(retry);
        service.release(retry);
        assertThat(service.reserve(event(Map.of("power", 1))).status()).isEqualTo(
            IdempotencyService.Status.PUBLISHED
        );
    }

    @Test
    void distinctValuesWithTheSameJavaHashAreNotDuplicates() {
        var first = Map.<String, Object>of("power", 1);
        var second = Map.<String, Object>of("power", 4294967296L);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(service.reserve(event(first)).key()).isNotEqualTo(
            service.reserve(event(second)).key()
        );
    }

    @Test
    void nestedKeyOrderAndNumericRepresentationDoNotChangeIdentity() {
        var first = new LinkedHashMap<String, Object>();
        first.put("a", 1);
        first.put("b", false);
        var second = new LinkedHashMap<String, Object>();
        second.put("b", false);
        second.put("a", new BigDecimal("1.00"));
        assertThat(service.reserve(event(Map.of("fields", first))).key()).isEqualTo(
            service.reserve(event(Map.of("fields", second))).key()
        );
    }

    @Test
    void exactDecimalValuesRemainDistinctAndNestedInputsAreFrozen() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("decimal", new BigDecimal("9007199254740992.001"));
        var first = service.reserve(event(Map.of("fields", fields)));
        fields.put("decimal", new BigDecimal("9007199254740992.002"));
        assertThat(service.reserve(event(Map.of("fields", fields))).key()).isNotEqualTo(
            first.key()
        );
        service.release(first);
        var retry = service.reserve(
            event(Map.of("fields", Map.of("decimal", new BigDecimal("9007199254740992.001"))))
        );
        assertThat(retry.event()).isEqualTo(first.event());
    }

    @Test
    void completedRecordsExpireInsteadOfGrowingForever() {
        var first = service.reserve(event(Map.of("power", 1)));
        service.complete(first);
        clock.advance(Duration.ofHours(24));
        var next = service.reserve(event(Map.of("power", 1)));
        assertThat(next.status()).isEqualTo(IdempotencyService.Status.ACQUIRED);
        assertThat(next.event().eventId()).isNotEqualTo(first.event().eventId());
    }

    private TelemetryReceivedEvent event(Map<String, Object> metrics) {
        return new TelemetryReceivedEvent(
            UUID.randomUUID(),
            device,
            "plug-001",
            Instant.parse("2026-06-13T08:00:00Z"),
            metrics,
            clock.instant()
        );
    }

    private static class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-06-13T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
