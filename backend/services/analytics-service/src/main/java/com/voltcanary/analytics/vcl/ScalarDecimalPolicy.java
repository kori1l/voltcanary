package com.voltcanary.analytics.vcl;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pinned VCL decimal policy: overflow rejects; only scale rounding may be approved. */
public record ScalarDecimalPolicy(int precision, int scale, Rounding rounding) {
    public enum Rounding { REJECT, HALF_EVEN }

    public ScalarDecimalPolicy {
        if (precision != 38 || scale < 0 || scale > 12 || rounding == null) {
            throw new IllegalArgumentException("Invalid VCL decimal policy");
        }
    }

    public RoundingMode mode() { return rounding == Rounding.REJECT ? RoundingMode.UNNECESSARY : RoundingMode.HALF_EVEN; }

    BigDecimal apply(BigDecimal value) {
        var result = value.setScale(scale, mode());
        if (result.precision() > precision) throw new ArithmeticException("Decimal overflow");
        return result.signum() == 0 ? BigDecimal.ZERO : result.stripTrailingZeros();
    }
}
