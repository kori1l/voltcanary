package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import com.voltcanary.vcl.VclSourceBindingDescription;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Static typing pins only. Actual capture/readiness and grant checks remain separate owner gates. */
public final class SourceBindingSnapshot implements VclMetadata {
    private final Map<SourceBindingSnapshots.Target, VclSourceBindingDescription> values;
    private final Function<SourceBindingSnapshots.Target, VclSourceBindingDescription> refresh;
    private final String scope;

    SourceBindingSnapshot(Map<SourceBindingSnapshots.Target, VclSourceBindingDescription> values,
            Function<SourceBindingSnapshots.Target, VclSourceBindingDescription> refresh) {
        this.values = Map.copyOf(values);
        this.refresh = refresh;
        scope = values.values().iterator().next().accessScopeDigest();
        if (values.values().stream().anyMatch(value -> !scope.equals(value.accessScopeDigest()))) throw new VclException("ACCESS_SCOPE_CHANGED", "");
    }

    @Override
    public String accessScopeDigest() {
        values.forEach(this::reauthorize);
        return scope;
    }

    public Optional<VclSourceBindingDescription> description(UUID id, long revision) {
        var target = new SourceBindingSnapshots.Target(id, revision);
        var value = values.get(target);
        if (value != null) reauthorize(target, value);
        return Optional.ofNullable(value);
    }

    private void reauthorize(SourceBindingSnapshots.Target target, VclSourceBindingDescription original) {
        var current = refresh.apply(target);
        if (!scope.equals(current.accessScopeDigest())) throw new VclException("ACCESS_SCOPE_CHANGED", "");
        if (!original.equals(current)) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
    }

    /** Metadata and latest-sample delivery cannot attest the normalized temporal capture contract. */
    public void requireRuntimeReady() {
        accessScopeDigest();
        if (values.values().stream().anyMatch(value -> !value.ruleExecutionReady())) throw new VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
    }

    @Override public Optional<Snapshot> dataset(UUID id, long revision) { return Optional.empty(); }
    @Override public Optional<Snapshot> binding(UUID id, long revision) {
        return description(id, revision).map(value -> new Snapshot(id, revision, value.definition()));
    }
    @Override public boolean permits(Resource kind, UUID id) { return false; }
    @Override public String toString() { return "SourceBindingSnapshot[targets=" + values.size() + "]"; }
}
