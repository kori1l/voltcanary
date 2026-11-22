package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclEvidencePolicyDescription;
import com.voltcanary.vcl.VclException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Real policy inspection bound to a separately authenticated immutable grant snapshot. */
@Component
public class EvidencePolicySnapshots {

    private final ConfigurableOwnerReads reads;

    public EvidencePolicySnapshots(
        RestClient.Builder builder,
        AnalyticsDownstreamProperties properties
    ) {
        var settings = properties.getAlertService();
        reads = new ConfigurableOwnerReads(builder, settings.getBaseUrl(), settings.getTimeout());
    }

    public EvidencePolicySnapshot capture(
        String authorization,
        UUID actor,
        UUID account,
        UUID rule,
        UUID id,
        AutomationGrantSnapshot grant
    ) {
        if (actor == null || account == null) throw new VclException(
            "OWNER_AUTHENTICATION_REQUIRED",
            ""
        );
        if (id == null || rule == null || grant == null) throw new VclException(
            "OWNER_TARGET_INVALID",
            ""
        );
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var initial = describe(authorization, actor, account, rule, id, grant, deadline);
        return new EvidencePolicySnapshot(initial, () ->
            describe(authorization, actor, account, rule, id, grant, deadline)
        );
    }

    private VclEvidencePolicyDescription describe(
        String authorization,
        UUID actor,
        UUID account,
        UUID rule,
        UUID id,
        AutomationGrantSnapshot grant,
        long deadline
    ) {
        try {
            var value = VclEvidencePolicyDescription.parse(
                reads.evidencePolicy(authorization, id, deadline)
            );
            if (
                !id.equals(value.id()) ||
                !actor.equals(value.actorId()) ||
                !account.equals(value.accountId()) ||
                !rule.equals(value.ruleId())
            ) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            if (
                !value.usable() || !Instant.now().isBefore(value.expiresAt())
            ) throw new VclException("EVIDENCE_POLICY_INACTIVE", "");
            grant.requirePolicy(value);
            return value;
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }
}
