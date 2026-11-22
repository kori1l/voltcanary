package com.voltcanary.alert.configurable.application;

import com.voltcanary.alert.configurable.domain.RuleReservation;
import com.voltcanary.alert.configurable.port.RuleAccountAuthority;
import com.voltcanary.alert.configurable.port.RuleReservationStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Fresh authority comes from the device owner, never the local quota/serialization row. */
@Service
@RequiredArgsConstructor
public class ConfigurableRuleReservations {

    private final RuleAccountAuthority access;
    private final RuleReservationStore store;

    public RuleReservation reserve(String actorId, byte[] body) {
        var request = RuleReservationRequests.parse(body);
        UUID actor = UUID.fromString(actorId);
        UUID account = access.account(actor);
        return store.reserve(account, actor, request.outputKeys(), request.idempotencyKey());
    }

    public RuleReservation inspect(String actorId, UUID reviewId) {
        return store.inspect(
            access.account(UUID.fromString(actorId)),
            UUID.fromString(actorId),
            reviewId
        );
    }
}
