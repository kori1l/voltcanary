package com.voltcanary.device.configurable.domain;

import java.util.Objects;
import java.util.UUID;

/** Explicit context, not permission evidence. The stored grant and current membership supply authority. */
public record NativeSharedAccess(UUID account, UUID actor, UUID workspace, UUID dataset, UUID grant) {
    public NativeSharedAccess {
        Objects.requireNonNull(account);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(dataset);
        Objects.requireNonNull(grant);
    }
}
