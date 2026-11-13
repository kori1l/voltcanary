package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.UUID;

/** Account-owned immutable WGS84 circle revision. Coordinates use longitude, latitude order. */
public record VclCircleRevision(UUID id, UUID accountId, long revision, Definition geometry, String digest) {
    private static final VclParser PARSER = new VclParser();

    public record Definition(double longitude, double latitude, String radiusMetres) {
        public Definition {
            if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                    || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90
                    || radiusMetres == null || radiusMetres.length() > 32
                    || !radiusMetres.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,12})?")) {
                throw new VclException("SPATIAL_CIRCLE_INVALID", "");
            }
            var radius = new BigDecimal(radiusMetres);
            if (radius.signum() <= 0 || radius.compareTo(new BigDecimal("20100000")) > 0) {
                throw new VclException("SPATIAL_CIRCLE_INVALID", "");
            }
            radiusMetres = radius.stripTrailingZeros().toPlainString();
            if (longitude == 0) longitude = 0;
            if (latitude == 0) latitude = 0;
        }
    }

    public VclCircleRevision {
        if (id == null || accountId == null || revision < 1 || geometry == null
                || !digest(id, accountId, revision, geometry).equals(digest)) {
            throw new VclException("SPATIAL_REVISION_INVALID", "");
        }
    }

    public static VclCircleRevision create(UUID id, UUID account, long revision, Definition geometry) {
        return new VclCircleRevision(id, account, revision, geometry, digest(id, account, revision, geometry));
    }

    public static VclCircleRevision parse(byte[] bytes) {
        try {
            var value = PARSER.readJson(bytes);
            var geometry = value.path("geometry");
            if (!value.isObject() || value.size() != 5 || !value.path("revision").isIntegralNumber()
                    || !value.path("revision").canConvertToLong() || !geometry.isObject() || geometry.size() != 3
                    || !geometry.path("longitude").isNumber() || !geometry.path("latitude").isNumber()) {
                throw new IllegalArgumentException();
            }
            return new VclCircleRevision(UUID.fromString(value.path("id").textValue()),
                    UUID.fromString(value.path("accountId").textValue()), value.path("revision").longValue(),
                    new Definition(geometry.path("longitude").doubleValue(), geometry.path("latitude").doubleValue(),
                            geometry.path("radiusMetres").textValue()), value.path("digest").textValue());
        } catch (RuntimeException invalid) { throw new VclException("OWNER_SNAPSHOT_INVALID", ""); }
    }

    private static String digest(UUID id, UUID account, long revision, Definition geometry) {
        var payload = JsonNodeFactory.instance.objectNode().put("profile", "VCL_CIRCLE/1")
                .put("id", id.toString()).put("account", account.toString()).put("revision", Long.toString(revision))
                .put("longitude", geometry.longitude()).put("latitude", geometry.latitude()).put("radiusMetres", geometry.radiusMetres());
        return VclCanonical.sha256(VclCanonical.encode(payload));
    }
}
