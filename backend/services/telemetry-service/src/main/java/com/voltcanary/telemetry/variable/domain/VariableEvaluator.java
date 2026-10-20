package com.voltcanary.telemetry.variable.domain;

import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.common.events.telemetry.VariableExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure, bounded evaluator shared by live processing and preview in telemetry. */
public final class VariableEvaluator {
    public record Step(String operation, double value, String unit) { }
    public record Result(Double value, String unit, String status, List<Step> steps) { }
    private record Value(double number, String unit) { }

    public Result evaluate(VariableContract contract, Map<String, Double> inputs) {
        List<Step> steps = new ArrayList<>();
        try {
            require(contract != null && "v1".equals(contract.languageVersion())
                    && contract.inputs() != null && !contract.inputs().isEmpty()
                    && contract.inputs().size() <= 8 && contract.unit() != null, "INVALID_CONTRACT");
            require(inputs != null && inputs.keySet().equals(contract.inputs().keySet()), "MISSING_INPUT");
            for (var entry : contract.inputs().entrySet()) {
                require(entry.getValue() != null && entry.getValue().unit() != null, "INVALID_CONTRACT");
                Double value = inputs.get(entry.getKey());
                require(value != null && Double.isFinite(value), "INVALID_INPUT");
                require(entry.getValue().minimum() == null || value >= entry.getValue().minimum(), "INPUT_OUT_OF_RANGE");
                require(entry.getValue().maximum() == null || value <= entry.getValue().maximum(), "INPUT_OUT_OF_RANGE");
            }
            var value = visit(contract.expression(), contract, inputs, steps, 0);
            require(compatible(value.unit(), contract.unit()), "UNIT_MISMATCH");
            require(contract.minimum() == null || value.number() >= contract.minimum(), "OUTPUT_OUT_OF_RANGE");
            require(contract.maximum() == null || value.number() <= contract.maximum(), "OUTPUT_OUT_OF_RANGE");
            return new Result(value.number(), contract.unit(), "OK", List.copyOf(steps));
        } catch (IllegalArgumentException failure) {
            return new Result(null, contract == null ? "" : contract.unit(), failure.getMessage(), List.copyOf(steps));
        }
    }

    private Value visit(VariableExpression node, VariableContract contract, Map<String, Double> inputs,
            List<Step> steps, int depth) {
        require(node != null && node.op() != null && depth <= 12 && steps.size() < 64, "EXPRESSION_LIMIT");
        Value result;
        switch (node.op()) {
            case "INPUT" -> {
                require(node.left() == null && node.right() == null && node.value() == null
                        && node.alias() != null && contract.inputs().containsKey(node.alias()), "INVALID_INPUT_NODE");
                result = new Value(inputs.get(node.alias()), contract.inputs().get(node.alias()).unit());
            }
            case "CONSTANT" -> {
                require(node.left() == null && node.right() == null && node.alias() == null
                        && node.value() != null && Double.isFinite(node.value()), "INVALID_CONSTANT");
                result = new Value(node.value(), "");
            }
            case "CONVERT" -> {
                require(node.value() == null && node.right() == null && node.alias() != null, "INVALID_CONVERSION");
                var input = visit(node.left(), contract, inputs, steps, depth + 1);
                double celsius = switch (input.unit()) {
                    case "C", "°C" -> input.number();
                    case "F", "°F" -> (input.number() - 32) * 5 / 9;
                    case "K" -> input.number() - 273.15;
                    default -> throw new IllegalArgumentException("UNSUPPORTED_CONVERSION");
                };
                double converted = switch (node.alias()) {
                    case "C", "°C" -> celsius;
                    case "F", "°F" -> celsius * 9 / 5 + 32;
                    case "K" -> celsius + 273.15;
                    default -> throw new IllegalArgumentException("UNSUPPORTED_CONVERSION");
                };
                result = new Value(converted, node.alias());
            }
            case "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE" -> {
                require(node.alias() == null && node.value() == null, "INVALID_BINARY_NODE");
                var left = visit(node.left(), contract, inputs, steps, depth + 1);
                var right = visit(node.right(), contract, inputs, steps, depth + 1);
                String unit = arithmeticUnit(node.op(), left.unit(), right.unit());
                double value = switch (node.op()) {
                    case "ADD" -> left.number() + right.number();
                    case "SUBTRACT" -> left.number() - right.number();
                    case "MULTIPLY" -> left.number() * right.number();
                    default -> {
                        require(right.number() != 0, "DIVISION_BY_ZERO");
                        yield left.number() / right.number();
                    }
                };
                result = new Value(value, unit);
            }
            default -> throw new IllegalArgumentException("UNSUPPORTED_OPERATION");
        }
        require(Double.isFinite(result.number()), "NONFINITE_OUTPUT");
        require(steps.size() < 64, "EXPRESSION_LIMIT");
        steps.add(new Step(node.op(), result.number(), result.unit()));
        return result;
    }

    private String arithmeticUnit(String op, String left, String right) {
        if ("ADD".equals(op) || "SUBTRACT".equals(op)) {
            require(compatible(left, right) || left.isEmpty() || right.isEmpty(), "UNIT_MISMATCH");
            return left.isEmpty() ? right : left;
        }
        if ("MULTIPLY".equals(op)) {
            if (left.isEmpty() || "ratio".equals(left)) return right;
            if (right.isEmpty() || "ratio".equals(right)) return left;
            if ("V".equals(left) && "A".equals(right) || "A".equals(left) && "V".equals(right)) return "W";
        } else {
            if (right.isEmpty() || "ratio".equals(right)) return left;
            if (compatible(left, right)) return "ratio";
        }
        throw new IllegalArgumentException("UNSUPPORTED_UNIT_COMBINATION");
    }

    private boolean compatible(String first, String second) {
        if (first == null || second == null) return false;
        return first.equals(second) || Set.of("C", "°C").contains(first) && Set.of("C", "°C").contains(second)
                || Set.of("F", "°F").contains(first) && Set.of("F", "°F").contains(second);
    }

    private void require(boolean condition, String diagnostic) {
        if (!condition) throw new IllegalArgumentException(diagnostic);
    }
}
