package com.voltcanary.alert.configurable.domain;

import java.util.UUID;

public record GrantRevocation(UUID id, UUID receiptId, UUID grantId, UUID accountId, UUID actorId,
        long expectedEpoch, String idempotencyKey, String status, Long resultEpoch) {
    @Override public String toString() { return "GrantRevocation[id=" + id + ", status=" + status + "]"; }
}
