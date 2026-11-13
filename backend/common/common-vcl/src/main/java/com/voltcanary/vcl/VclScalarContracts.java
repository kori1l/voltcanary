package com.voltcanary.vcl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;

/** Shared versioned kernel contract, not a claim that an owner has activated these capabilities. */
public final class VclScalarContracts {
    public static final String VERSION = "vcl-scalar-kernels-1";
    public static final String SPATIAL_VERSION = "geographiclib-java-2.1-wgs84-nm1-v1";
    public static final BigDecimal SPATIAL_ERROR_METRES = new BigDecimal("0.000001");
    public static final int SPATIAL_OUTPUT_SCALE = 9;

    private VclScalarContracts() { }

    public static ObjectNode descriptor() {
        var result = JsonNodeFactory.instance.objectNode().put("version", VERSION).put("spatialVersion", SPATIAL_VERSION)
                .put("distanceOutputScale", SPATIAL_OUTPUT_SCALE).put("distanceQuantizationRounding", "HALF_EVEN")
                .put("distanceErrorBoundMetres", SPATIAL_ERROR_METRES.toPlainString()).put("missingAccuracy", "UNKNOWN");
        result.putArray("membershipShapes").add("CIRCLE");
        return result;
    }
}
