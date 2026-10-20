package com.voltcanary.common.events.telemetry;

/** Version-one wire AST. Interpretation belongs to the owning services. */
public record VariableExpression(String op, Double value, String alias,
        VariableExpression left, VariableExpression right) {
    public static VariableExpression input(String alias) {
        return new VariableExpression("INPUT", null, alias, null, null);
    }
    public static VariableExpression constant(double value) {
        return new VariableExpression("CONSTANT", value, null, null, null);
    }
}
