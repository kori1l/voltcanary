package com.voltcanary.device.configurable.port;

import com.voltcanary.device.configurable.domain.NativeCollectionReview;
import com.voltcanary.device.configurable.domain.NativeDataset;
import com.voltcanary.device.configurable.domain.NativeSchema;
import java.util.List;
import java.util.UUID;

public interface NativeCollectionReviewStore {
    NativeCollectionReview reserve(UUID account, UUID actor, List<String> keys, String idempotencyKey);
    NativeCollectionReview inspect(UUID account, UUID actor, UUID reviewId);
    NativeCollectionReview prepare(UUID account, UUID actor, UUID reviewId, NativeSchema schema);
    NativeCollectionReview begin(UUID account, UUID actor, UUID reviewId, long revision, String digest, String key);
    NativeCollectionReview complete(UUID account, UUID actor, UUID reviewId, NativeDataset dataset);
    NativeCollectionReview unknown(UUID account, UUID actor, UUID reviewId, String code);
}
