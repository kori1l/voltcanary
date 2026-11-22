package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record VclType(
    Kind kind,
    boolean nullable,
    String unit,
    String referenceTarget,
    java.util.Set<String> enumValues
) {
    public enum Kind {
        BOOLEAN,
        INT64,
        DECIMAL,
        TEXT,
        ENUM,
        DATE,
        INSTANT,
        DURATION,
        POINT,
        REF,
        INTERVAL,
        NULL,
    }

    public static final VclType BOOLEAN = new VclType(Kind.BOOLEAN, false, "1", null);

    public VclType {
        Objects.requireNonNull(kind);
        unit = unit == null ? "1" : unit;
        enumValues = java.util.Set.copyOf(enumValues);
    }

    public VclType(Kind kind, boolean nullable, String unit, String referenceTarget) {
        this(kind, nullable, unit, referenceTarget, java.util.Set.of());
    }

    public static VclType from(JsonNode field) {
        String target = null;
        if (field.has("target")) {
            var ref = field.path("target");
            target = ref.path("kind").asText().equals("DATASET")
                ? "DATASET:" + ref.path("datasetId").asText().toLowerCase()
                : "RESOURCE:" + ref.path("resourceKind").asText();
        }
        Kind kind;
        try {
            kind = Kind.valueOf(field.path("type").asText());
        } catch (IllegalArgumentException invalid) {
            throw new VclException("INVALID_FIELD_TYPE", "");
        }
        var values = new java.util.LinkedHashSet<String>();
        field.path("values").forEach(value -> values.add(value.asText()));
        if (kind == Kind.ENUM && field.has("value")) values.add(field.path("value").asText());
        return new VclType(
            kind,
            field.path("nullable").asBoolean(false),
            field.path("unit").asText("1"),
            target,
            values
        );
    }

    public boolean numeric() {
        return kind == Kind.INT64 || kind == Kind.DECIMAL;
    }

    public void compatible(VclType other, String path) {
        if (kind == Kind.NULL || other.kind == Kind.NULL) return;
        VclException.require(kind == other.kind, "TYPE_MISMATCH", path);
        VclException.require(unit.equals(other.unit), "UNIT_MISMATCH", path);
        if (kind == Kind.REF && referenceTarget != null && other.referenceTarget != null) {
            VclException.require(
                referenceTarget.equals(other.referenceTarget),
                "REF_TYPE_MISMATCH",
                path
            );
        }
        if (kind == Kind.ENUM && !enumValues.isEmpty() && !other.enumValues.isEmpty()) {
            VclException.require(
                enumValues.containsAll(other.enumValues) ||
                    other.enumValues.containsAll(enumValues),
                "ENUM_VALUE_INVALID",
                path
            );
        }
    }
}
