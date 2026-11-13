package com.voltcanary.device.configurable.port;

import com.voltcanary.device.configurable.domain.NativeRowReview;
import com.voltcanary.vcl.VclDocument;
import java.util.UUID;

public interface NativeRowReviews {
    NativeRowReview reserve(UUID account, UUID actor, UUID dataset, long schemaRevision, String key);
    NativeRowReview prepare(UUID account, UUID actor, UUID review, VclDocument document);
    NativeRowReview begin(UUID account, UUID actor, UUID review, long revision, String digest, String key);
    NativeRowReview inspect(UUID account, UUID actor, UUID review);
    NativeRowReview outcome(UUID account, UUID actor, UUID review, String status, String code);
}
