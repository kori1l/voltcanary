package com.voltcanary.analytics.vcl.adapter;

import com.voltcanary.vcl.VclDatasetDescription;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclMetadata;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Immutable native dependency pins with fresh owner authorization at compiler scope fences. */
public final class NativeDatasetSnapshot implements VclMetadata {

    private final Map<NativeDatasetSnapshots.Target, VclDatasetDescription> descriptions;
    private final Function<NativeDatasetSnapshots.Target, VclDatasetDescription> refresh;
    private final String scope;

    NativeDatasetSnapshot(
        Map<NativeDatasetSnapshots.Target, VclDatasetDescription> descriptions,
        Function<NativeDatasetSnapshots.Target, VclDatasetDescription> refresh
    ) {
        this.descriptions = Map.copyOf(descriptions);
        this.refresh = refresh;
        scope = descriptions.values().iterator().next().accessScopeDigest();
        if (
            descriptions
                .values()
                .stream()
                .anyMatch(value -> !scope.equals(value.accessScopeDigest()))
        ) {
            throw new VclException("ACCESS_SCOPE_CHANGED", "");
        }
    }

    @Override
    public String accessScopeDigest() {
        reauthorize();
        return scope;
    }

    /** Re-read all original pins. Changed heads require a new review, never silent recompilation. */
    public void reauthorize() {
        descriptions.forEach(this::reauthorize);
    }

    void reauthorizeSealedSchema() {
        descriptions.forEach((target, original) -> {
            var current = refresh.apply(target);
            if (!scope.equals(current.accessScopeDigest())) throw new VclException(
                "ACCESS_SCOPE_CHANGED",
                ""
            );
            if (!original.schemaDigest().equals(current.schemaDigest())) {
                throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
            }
        });
    }

    Optional<Snapshot> sealedSchema(UUID id, long schemaRevision) {
        var original = descriptions.get(new NativeDatasetSnapshots.Target(id, schemaRevision));
        return Optional.ofNullable(original).map(value ->
            new Snapshot(id, schemaRevision, value.definition())
        );
    }

    private void reauthorize(NativeDatasetSnapshots.Target target, VclDatasetDescription original) {
        var current = refresh.apply(target);
        if (!scope.equals(current.accessScopeDigest())) throw new VclException(
            "ACCESS_SCOPE_CHANGED",
            ""
        );
        if (!original.equals(current)) throw new VclException("METADATA_SNAPSHOT_CHANGED", "");
    }

    @Override
    public Optional<Snapshot> dataset(UUID id, long schemaRevision) {
        return description(id, schemaRevision).map(value ->
            new Snapshot(id, schemaRevision, value.definition())
        );
    }

    public Optional<VclDatasetDescription> description(UUID id, long schemaRevision) {
        var target = new NativeDatasetSnapshots.Target(id, schemaRevision);
        var original = descriptions.get(target);
        if (original != null) reauthorize(target, original);
        return Optional.ofNullable(original);
    }

    // A native data description grants no binding, reference, execution or source authority.
    @Override
    public Optional<Snapshot> binding(UUID id, long schemaRevision) {
        return Optional.empty();
    }

    @Override
    public boolean permits(Resource kind, UUID id) {
        return false;
    }

    @Override
    public String toString() {
        return "NativeDatasetSnapshot[targets=" + descriptions.size() + "]";
    }
}
