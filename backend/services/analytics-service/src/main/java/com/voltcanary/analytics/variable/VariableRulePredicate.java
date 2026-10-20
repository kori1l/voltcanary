package com.voltcanary.analytics.variable;

public final class VariableRulePredicate {
    private VariableRulePredicate() { }
    public static boolean matches(String condition, double value, double threshold) {
        if (!Double.isFinite(value) || !Double.isFinite(threshold)) return false;
        return switch (condition) {
            case "ABOVE_THRESHOLD" -> value > threshold;
            case "BELOW_THRESHOLD" -> value < threshold;
            default -> throw new IllegalArgumentException("Unsupported variable condition");
        };
    }
}
