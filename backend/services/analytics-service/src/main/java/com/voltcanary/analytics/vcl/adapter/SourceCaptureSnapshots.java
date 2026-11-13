package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.analytics.config.AnalyticsDownstreamProperties;
import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclSourceCaptureDescription;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Source-authorized journal generations, never synthesized from schema snapshots or model read sets. */
@Component
public class SourceCaptureSnapshots {
    private final ConfigurableOwnerReads reads;
    public SourceCaptureSnapshots(RestClient.Builder builder, AnalyticsDownstreamProperties properties) {
        var settings = properties.getDeviceService();
        reads = new ConfigurableOwnerReads(builder, settings.getBaseUrl(), settings.getTimeout());
    }
    public SourceCaptureSnapshot capture(String authorization, UUID actor, UUID account, UUID binding, long revision) {
        return capture(authorization, actor, account, binding, revision, null);
    }

    SourceCaptureSnapshot capture(String authorization, UUID actor, UUID account, UUID binding, long revision, SourceCaptureSnapshot.Cut cut) {
        if (actor == null || account == null) throw new VclException("OWNER_AUTHENTICATION_REQUIRED", "");
        if (binding == null || revision < 1) throw new VclException("OWNER_TARGET_INVALID", "");
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var initial = describe(authorization, actor, account, binding, revision, deadline);
        return new SourceCaptureSnapshot(initial, cut, () -> describe(authorization, actor, account, binding, revision, deadline), request -> {
            try {
                var page = com.voltcanary.vcl.VclObservationPage.parse(reads.sourceObservations(authorization, binding, request.bytes(), deadline));
                if (!request.equals(page.request())) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
                return page;
            } catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
        }, proof -> {
            try {
                byte[] pin = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(initial.capture().pin());
                return com.voltcanary.vcl.VclSourceCoverage.parse(reads.sourceCoverage(authorization, binding, proof, pin, deadline));
            } catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
            catch (java.io.IOException failure) { throw new VclException("OWNER_SNAPSHOT_INVALID", ""); }
        });
    }
    private VclSourceCaptureDescription describe(String authorization, UUID actor, UUID account, UUID binding, long revision, long deadline) {
        try {
            var value = VclSourceCaptureDescription.parse(reads.sourceCapture(authorization, binding, revision, deadline));
            var source = value.source();
            if (!actor.equals(source.actorId()) || !account.equals(source.accountId()) || !binding.equals(source.id()) || source.schemaRevision() != revision) {
                throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            }
            if (!source.sourceActive()) throw new VclException("SOURCE_NOT_ACTIVE", "");
            if (!value.capture().journalReady()) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
            return value;
        } catch (ConfigurableOwnerReads.Failure failure) { throw new VclException(failure.code(), ""); }
    }
}
