package com.voltcanary.device.configurable.port;

import com.voltcanary.device.configurable.domain.NativeRowReview;
import java.util.UUID;

public interface NativeRowMutations {
    NativeRowReview apply(UUID account, UUID actor, UUID review);
}
