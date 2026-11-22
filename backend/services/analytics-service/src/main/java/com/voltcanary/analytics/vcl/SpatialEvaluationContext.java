package com.voltcanary.analytics.vcl;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pinned kernel/data plus fresh owner authorization. No latest-revision or remote lookup path. */
public record SpatialEvaluationContext(
    String kernelVersion,
    String expectedSnapshotDigest,
    SpatialFeatureSnapshot snapshot,
    Set<UUID> authorizedFeatureIds
) {
    public SpatialEvaluationContext {
        Objects.requireNonNull(kernelVersion);
        Objects.requireNonNull(snapshot);
        if (
            expectedSnapshotDigest == null ||
            !expectedSnapshotDigest.matches("[0-9a-f]{64}") ||
            authorizedFeatureIds.size() > 128
        ) {
            throw new IllegalArgumentException("Invalid spatial execution pins");
        }
        authorizedFeatureIds = Set.copyOf(authorizedFeatureIds);
    }

    public static SpatialEvaluationContext distanceOnly(String scopeDigest) {
        var snapshot = new SpatialFeatureSnapshot(scopeDigest, Map.of());
        return new SpatialEvaluationContext(
            Wgs84ScalarKernel.VERSION,
            snapshot.digest(),
            snapshot,
            Set.of()
        );
    }

    @Override
    public String toString() {
        return "SpatialEvaluationContext[kernelVersion=" + kernelVersion + "]";
    }
}
