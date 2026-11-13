package com.voltcanary.alert.configurable.port;

import com.voltcanary.alert.configurable.domain.EvidencePolicy;
import com.voltcanary.alert.configurable.domain.EvidencePolicyDefinition;
import com.voltcanary.alert.configurable.domain.EvidencePolicyReview;
import java.util.UUID;

public interface EvidencePolicyStore {
    record Inspection(EvidencePolicy policy, boolean usable) { }
    java.util.Optional<EvidencePolicyReview> reservation(UUID account, UUID actor, UUID grant, long epoch, String key);
    EvidencePolicyReview reserve(UUID account, UUID actor, UUID grant, long epoch, String key);
    EvidencePolicyReview inspect(UUID account, UUID actor, UUID review);
    EvidencePolicyReview prepare(UUID account, UUID actor, UUID review, EvidencePolicyDefinition definition);
    EvidencePolicyReview begin(UUID account, UUID actor, UUID review, long revision, String digest, String key);
    EvidencePolicyReview complete(UUID account, UUID actor, UUID review);
    EvidencePolicyReview unknown(UUID account, UUID actor, UUID review, String code);
    EvidencePolicyReview reconcile(UUID account, UUID actor, UUID review);
    Inspection policy(UUID account, UUID actor, UUID policy);
}
