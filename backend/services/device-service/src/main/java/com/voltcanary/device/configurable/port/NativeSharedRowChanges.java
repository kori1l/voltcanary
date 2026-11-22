package com.voltcanary.device.configurable.port;

import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.device.configurable.domain.NativeSharedAccess;
import com.voltcanary.vcl.VclDocument;
import java.util.UUID;

public interface NativeSharedRowChanges {
    NativeRowReview reserve(NativeSharedAccess access, long schemaRevision, String key);
    NativeRowReview prepare(NativeSharedAccess access, UUID review, VclDocument document);
    NativeRowReview inspect(NativeSharedAccess access, UUID review);
    NativeRowReview apply(
        NativeSharedAccess access,
        UUID review,
        long revision,
        String digest,
        String key
    );
}
