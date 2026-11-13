package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Authenticated source owner snapshots; account/actor claims from VCL or model JSON are never accepted. */
@Component
public class SourceBindingSnapshots {
    public record Target(UUID id, long revision) {
        public Target {
            java.util.Objects.requireNonNull(id);
            if (revision < 1) throw new IllegalArgumentException("Invalid binding revision");
        }
    }
    private final ConfigurableOwnerReads reads;

    public SourceBindingSnapshots(RestClient.Builder builder, AnalyticsDownstreamProperties properties) {
        var settings = properties.getDeviceService();
        reads = new ConfigurableOwnerReads(builder, settings.getBaseUrl(), settings.getTimeout());
    }

    public SourceBindingSnapshot capture(String authorization, UUID actorId, UUID accountId, List<Target> targets) {
        var requested = List.copyOf(targets);
        if (actorId == null || accountId == null) throw new VclException("OWNER_AUTHENTICATION_REQUIRED", "");
        if (requested.isEmpty() || requested.size() > 64 || requested.stream().map(Target::id).distinct().count() != requested.size()) {
            throw new VclException("OWNER_TARGET_LIMIT", "");
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var values = new LinkedHashMap<Target, VclSourceBindingDescription>();
        requested.forEach(target -> values.put(target, describe(authorization, actorId, accountId, target, deadline)));
        return new SourceBindingSnapshot(values, target -> describe(authorization, actorId, accountId, target, deadline));
    }

    private VclSourceBindingDescription describe(String authorization, UUID actor, UUID account, Target target, long deadline) {
        try {
            var value = VclSourceBindingDescription.parse(reads.sourceBinding(authorization, target.id(), target.revision(), deadline));
            if (!target.id().equals(value.id()) || target.revision() != value.schemaRevision()
                    || !actor.equals(value.actorId()) || !account.equals(value.accountId())) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            if (!value.sourceActive()) throw new VclException("SOURCE_NOT_ACTIVE", "");
            if (value.currentSchemaRevision() != value.schemaRevision()) throw new VclException("BINDING_REVISION_CHANGED", "");
            return value;
        } catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
    }
}
