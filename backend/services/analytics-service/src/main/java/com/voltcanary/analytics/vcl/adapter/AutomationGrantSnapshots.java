package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclAutomationGrantDescription;
import com.voltcanary.vcl.VclException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fixed authenticated owner route; no permission or epoch is accepted from caller JSON. */
@Component
public class AutomationGrantSnapshots {
    private final ConfigurableOwnerReads reads;

    public AutomationGrantSnapshots(RestClient.Builder builder, AnalyticsDownstreamProperties properties) {
        var settings = properties.getAlertService();
        reads = new ConfigurableOwnerReads(builder, settings.getBaseUrl(), settings.getTimeout());
    }

    public AutomationGrantSnapshot capture(String authorization, UUID actor, UUID account, UUID rule, UUID id) {
        if (actor == null || account == null) throw new VclException("OWNER_AUTHENTICATION_REQUIRED", "");
        if (rule == null || id == null) throw new VclException("OWNER_TARGET_INVALID", "");
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var initial = describe(authorization, actor, account, rule, id, deadline);
        return new AutomationGrantSnapshot(initial, () -> describe(authorization, actor, account, rule, id, deadline));
    }

    private VclAutomationGrantDescription describe(String authorization, UUID actor, UUID account, UUID rule, UUID id, long deadline) {
        try {
            var value = VclAutomationGrantDescription.parse(reads.automationGrant(authorization, id, deadline));
            if (!id.equals(value.id()) || !actor.equals(value.actorId()) || !account.equals(value.accountId()) || !rule.equals(value.ruleId())) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            if (!value.status().equals("ACTIVE") || !Instant.now().isBefore(value.expiresAt())) throw new VclException("AUTOMATION_GRANT_INACTIVE", "");
            return value;
        } catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
    }
}
