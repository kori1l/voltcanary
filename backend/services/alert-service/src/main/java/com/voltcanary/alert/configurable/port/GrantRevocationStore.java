package com.voltcanary.alert.configurable.port;

import com.voltcanary.alert.configurable.domain.GrantRevocation;
import java.util.UUID;

public interface GrantRevocationStore {
    GrantRevocation reserve(UUID actor, UUID grantId, long expectedEpoch, String key);
    GrantRevocation complete(UUID actor, UUID id);
    GrantRevocation inspect(UUID actor, UUID id);
    GrantRevocation unknown(UUID actor, UUID id);
}
