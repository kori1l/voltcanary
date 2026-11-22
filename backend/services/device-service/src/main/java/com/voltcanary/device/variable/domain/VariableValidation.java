package com.voltcanary.device.variable.domain;

import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.common.events.telemetry.VariableExpression;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Pure validation of the published language. Does not calculate sample values. */
public final class VariableValidation {

    private static final Set<String> RESERVED = Set.of(
        "latitude",
        "longitude",
        "speedKmh",
        "headingDeg",
        "ignition",
        "fuelPercent",
        "odometerKm",
        "activePowerW",
        "voltageV",
        "currentA",
        "energyWh",
        "rssi",
        "temperatureC",
        "vibrationMmS",
        "runtimeHours",
        "powerFactor",
        "frequencyHz",
        "configuredFields",
        "transport"
    );
    private static final Set<String> UNITS = Set.of(
        "",
        "C",
        "°C",
        "F",
        "°F",
        "K",
        "V",
        "A",
        "W",
        "Wh",
        "kW",
        "kWh",
        "%",
        "ratio",
        "s",
        "h",
        "km/h",
        "mm/s",
        "dBm"
    );

    private VariableValidation() {}

    public static void metadata(String key, String label, String origin, String unit) {
        require(
            key != null &&
                key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") &&
                !key.startsWith("var_") &&
                !RESERVED.contains(key),
            "Invalid or reserved variable key"
        );
        require(
            label != null &&
                !label.isBlank() &&
                label.length() <= 80 &&
                label.chars().noneMatch(Character::isISOControl),
            "Invalid label"
        );
        require(
            origin != null && Set.of("OBSERVED", "DERIVED").contains(origin),
            "Invalid variable origin"
        );
        require(unit != null && UNITS.contains(unit), "Unsupported unit");
    }

    public static void contract(VariableContract contract) {
        require(contract != null, "Variable contract is required");
        metadata(contract.key(), contract.label(), contract.origin(), contract.unit());
        require("v1".equals(contract.languageVersion()), "Unsupported expression language");
        require(
            contract.inputs() != null &&
                !contract.inputs().isEmpty() &&
                contract.inputs().size() <= 8,
            "Use one to eight inputs"
        );
        contract.inputs().forEach((alias, input) -> {
            require(
                alias != null && alias.matches("[a-zA-Z][a-zA-Z0-9_]{0,31}") && input != null,
                "Invalid input alias"
            );
            require(input.unit() != null && UNITS.contains(input.unit()), "Unsupported input unit");
            bounds(input.minimum(), input.maximum());
        });
        bounds(contract.minimum(), contract.maximum());
        Set<String> used = new HashSet<>();
        String inferred = unit(contract.expression(), contract.inputs(), used, new int[] { 0 }, 0);
        require(used.equals(contract.inputs().keySet()), "Every declared input must be used");
        require(compatible(inferred, contract.unit()), "Expression and output units differ");
        if ("OBSERVED".equals(contract.origin())) {
            require(
                "INPUT".equals(contract.expression().op()) && contract.inputs().size() == 1,
                "Observed variables must select exactly one input"
            );
        }
    }

    private static String unit(
        VariableExpression node,
        Map<String, VariableContract.Input> inputs,
        Set<String> used,
        int[] count,
        int depth
    ) {
        require(
            node != null && node.op() != null && ++count[0] <= 64 && depth <= 12,
            "Expression exceeds node/depth limit"
        );
        return switch (node.op()) {
            case "INPUT" -> {
                require(
                    node.value() == null &&
                        node.left() == null &&
                        node.right() == null &&
                        node.alias() != null &&
                        inputs.containsKey(node.alias()),
                    "Invalid input node"
                );
                used.add(node.alias());
                yield inputs.get(node.alias()).unit();
            }
            case "CONSTANT" -> {
                require(
                    node.alias() == null &&
                        node.left() == null &&
                        node.right() == null &&
                        node.value() != null &&
                        Double.isFinite(node.value()),
                    "Invalid constant node"
                );
                yield "";
            }
            case "CONVERT" -> {
                require(
                    node.value() == null &&
                        node.right() == null &&
                        node.alias() != null &&
                        Set.of("C", "°C", "F", "°F", "K").contains(node.alias()),
                    "Invalid conversion target"
                );
                String inputUnit = unit(node.left(), inputs, used, count, depth + 1);
                require(
                    Set.of("C", "°C", "F", "°F", "K").contains(inputUnit),
                    "Unsupported unit conversion"
                );
                yield node.alias();
            }
            case "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE" -> {
                require(node.alias() == null && node.value() == null, "Invalid binary node");
                String left = unit(node.left(), inputs, used, count, depth + 1);
                String right = unit(node.right(), inputs, used, count, depth + 1);
                yield arithmeticUnit(node.op(), left, right);
            }
            default -> throw new IllegalArgumentException("Unsupported expression operation");
        };
    }

    private static String arithmeticUnit(String operation, String left, String right) {
        if ("ADD".equals(operation) || "SUBTRACT".equals(operation)) {
            require(
                compatible(left, right) || left.isEmpty() || right.isEmpty(),
                "Addition requires compatible units or a literal offset"
            );
            return left.isEmpty() ? right : left;
        }
        if ("MULTIPLY".equals(operation)) {
            if (left.isEmpty() || "ratio".equals(left)) return right;
            if (right.isEmpty() || "ratio".equals(right)) return left;
            if (
                ("V".equals(left) && "A".equals(right)) || ("A".equals(left) && "V".equals(right))
            ) return "W";
        } else {
            if (right.isEmpty() || "ratio".equals(right)) return left;
            if (compatible(left, right)) return "ratio";
        }
        throw new IllegalArgumentException("Unsupported unit combination");
    }

    private static boolean compatible(String first, String second) {
        return (
            first.equals(second) ||
            (Set.of("C", "°C").contains(first) && Set.of("C", "°C").contains(second)) ||
            (Set.of("F", "°F").contains(first) && Set.of("F", "°F").contains(second))
        );
    }

    private static void bounds(Double minimum, Double maximum) {
        require(
            (minimum == null || Double.isFinite(minimum)) &&
                (maximum == null || Double.isFinite(maximum)) &&
                (minimum == null || maximum == null || minimum <= maximum),
            "Invalid numeric bounds"
        );
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
