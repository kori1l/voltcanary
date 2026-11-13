package com.voltcanary.alert.configurable.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.alert.configurable.domain.AutomationGrant;
import com.voltcanary.alert.configurable.domain.AutomationGrantScope;
import com.voltcanary.alert.configurable.domain.GrantReview;
import java.util.UUID;

public interface GrantReviewStore {
    GrantReview reserve(UUID account, UUID actor, UUID ruleReviewId, String key);
    GrantReview inspect(UUID account, UUID actor, UUID id);
    GrantReview prepare(UUID account, UUID actor, UUID id, AutomationGrantScope scope, JsonNode pins);
    GrantReview begin(UUID account, UUID actor, UUID id, long revision, String digest, String key);
    GrantReview complete(UUID account, UUID actor, UUID id);
    GrantReview unknown(UUID account, UUID actor, UUID id, String code);
    GrantReview reconcile(UUID account, UUID actor, UUID id);
    AutomationGrant grant(UUID account, UUID actor, UUID id);
}
