package com.voltcanary.alert.configurable.port;

import com.voltcanary.alert.configurable.domain.RuleReservation;
import java.util.List;
import java.util.UUID;

public interface RuleReservationStore {
    RuleReservation reserve(
        UUID accountId,
        UUID actorId,
        List<String> outputKeys,
        String idempotencyKey
    );
    RuleReservation inspect(UUID accountId, UUID actorId, UUID reviewId);
}
