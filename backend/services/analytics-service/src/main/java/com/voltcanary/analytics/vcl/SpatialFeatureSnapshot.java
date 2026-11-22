package com.voltcanary.analytics.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.voltcanary.vcl.VclCanonical;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owner-issued immutable local geometry revisions. This is not an HTTP/model input or an access grant. */
public final class SpatialFeatureSnapshot {

    public record Point(double longitude, double latitude) {
        public Point {
            if (
                !Double.isFinite(longitude) ||
                !Double.isFinite(latitude) ||
                longitude < -180 ||
                longitude > 180 ||
                latitude < -90 ||
                latitude > 90
            ) {
                throw new IllegalArgumentException("Invalid WGS84 point");
            }
        }
    }

    public record Circle(UUID id, long revision, Point centre, BigDecimal radiusMetres) {
        public Circle {
            Objects.requireNonNull(id);
            Objects.requireNonNull(centre);
            Objects.requireNonNull(radiusMetres);
            if (
                revision < 1 ||
                radiusMetres.signum() <= 0 ||
                radiusMetres.compareTo(new BigDecimal("20100000")) > 0 ||
                radiusMetres.precision() > 38 ||
                Math.max(radiusMetres.scale(), 0) > 12
            ) {
                throw new IllegalArgumentException("Invalid circle revision");
            }
            radiusMetres = radiusMetres.stripTrailingZeros();
        }

        @Override
        public String toString() {
            return "Circle[id=" + id + ", revision=" + revision + "]";
        }
    }

    private final String scopeDigest;
    private final Map<UUID, Circle> circles;
    private final String digest;

    public SpatialFeatureSnapshot(String scopeDigest, Map<UUID, Circle> circles) {
        if (scopeDigest == null || !scopeDigest.matches("[0-9a-f]{64}") || circles.size() > 128) {
            throw new IllegalArgumentException("Invalid spatial snapshot");
        }
        this.scopeDigest = scopeDigest;
        this.circles = Map.copyOf(circles);
        this.circles.forEach((id, circle) -> {
            if (!id.equals(circle.id())) throw new IllegalArgumentException(
                "Spatial identity mismatch"
            );
        });
        var json = JsonNodeFactory.instance
            .objectNode()
            .put("version", "spatial-circle-snapshot-1")
            .put("scopeDigest", scopeDigest);
        var features = json.putArray("features");
        this.circles
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(UUID::toString)))
            .forEach(entry -> {
                var circle = entry.getValue();
                var feature = features
                    .addObject()
                    .put("id", circle.id().toString())
                    .put("revision", Long.toString(circle.revision()))
                    .put("shape", "CIRCLE")
                    .put("radiusMetres", circle.radiusMetres().toPlainString());
                feature
                    .putObject("centre")
                    .put("longitude", circle.centre().longitude())
                    .put("latitude", circle.centre().latitude());
            });
        digest = VclCanonical.sha256(VclCanonical.encode(json));
    }

    public String scopeDigest() {
        return scopeDigest;
    }

    public Map<UUID, Circle> circles() {
        return circles;
    }

    public String digest() {
        return digest;
    }

    @Override
    public String toString() {
        return "SpatialFeatureSnapshot[digest=" + digest + "]";
    }
}
