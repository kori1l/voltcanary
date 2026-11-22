package com.voltcanary.ingestion.idempotency;

import java.time.Duration;

final class DeliveryRetention {

    private DeliveryRetention() {}

    static void validate(Duration retention, Duration lease, Duration acknowledgement) {
        if (
            retention == null ||
            lease == null ||
            acknowledgement == null ||
            acknowledgement.isNegative() ||
            lease.toMillis() <= 0 ||
            lease.compareTo(acknowledgement) <= 0 ||
            retention.compareTo(lease) <= 0
        ) {
            throw new IllegalArgumentException(
                "Idempotency retention must exceed its lease, and lease must exceed Kafka" +
                    " acknowledgement timeout"
            );
        }
    }
}
