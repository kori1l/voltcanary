package com.voltcanary.analytics.vcl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicMask;

/** Versioned WGS84 inverse geodesic, with explicit nanometre quantization and conservative error bound. */
public final class Wgs84ScalarKernel {

    public enum Region {
        INSIDE,
        OUTSIDE,
        UNCERTAIN,
    }

    public static final String VERSION = com.voltcanary.vcl.VclScalarContracts.SPATIAL_VERSION;
    public static final BigDecimal ERROR_BOUND_METRES =
        com.voltcanary.vcl.VclScalarContracts.SPATIAL_ERROR_METRES;
    public static final int OUTPUT_SCALE =
        com.voltcanary.vcl.VclScalarContracts.SPATIAL_OUTPUT_SCALE;

    private Wgs84ScalarKernel() {}

    public static BigDecimal distanceMetres(
        SpatialFeatureSnapshot.Point a,
        SpatialFeatureSnapshot.Point b
    ) {
        double distance = Geodesic.WGS84.Inverse(
            a.latitude(),
            a.longitude(),
            b.latitude(),
            b.longitude(),
            GeodesicMask.DISTANCE
        ).s12;
        if (!Double.isFinite(distance) || distance < 0) throw new ArithmeticException(
            "Invalid geodesic result"
        );
        var result = BigDecimal.valueOf(distance).setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN);
        return result.signum() == 0 ? BigDecimal.ZERO : result.stripTrailingZeros();
    }

    public static Region classify(
        SpatialFeatureSnapshot.Point point,
        SpatialFeatureSnapshot.Circle circle,
        BigDecimal accuracyMetres
    ) {
        if (accuracyMetres.signum() < 0) throw new IllegalArgumentException(
            "Negative spatial accuracy"
        );
        var distance = distanceMetres(point, circle.centre());
        var uncertainty = accuracyMetres.add(ERROR_BOUND_METRES);
        if (distance.add(uncertainty).compareTo(circle.radiusMetres()) <= 0) return Region.INSIDE;
        if (
            distance.subtract(uncertainty).compareTo(circle.radiusMetres()) > 0
        ) return Region.OUTSIDE;
        return Region.UNCERTAIN;
    }
}
