package com.voltcanary.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import com.voltcanary.common.events.telemetry.VariableContract;
import com.voltcanary.common.events.telemetry.VariableExpression;
import com.voltcanary.telemetry.variable.domain.VariableEvaluator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VariableEvaluatorTest {
    private final VariableEvaluator evaluator = new VariableEvaluator();
    private VariableContract contract(VariableExpression expression, Double minimum, Double maximum) {
        return new VariableContract(UUID.randomUUID(), 1, "temperature", "Temperature", "DERIVED", "C", "v1",
                Map.of("x", new VariableContract.Input("C", null, null)), expression, minimum, maximum);
    }
    @Test void failuresNeverProduceNumericSubstitutes() {
        var input = contract(VariableExpression.input("x"), -50.0, 150.0);
        assertThat(evaluator.evaluate(input, Map.of()).status()).isEqualTo("MISSING_INPUT");
        var invalid = evaluator.evaluate(input, Map.of("x", Double.NaN));
        assertThat(invalid.status()).isEqualTo("INVALID_INPUT"); assertThat(invalid.value()).isNull();
        assertThat(evaluator.evaluate(input, Map.of("x", 200.0)).status()).isEqualTo("OUTPUT_OUT_OF_RANGE");
        var divided = contract(new VariableExpression("DIVIDE", null, null, VariableExpression.input("x"), VariableExpression.constant(0)), null, null);
        assertThat(evaluator.evaluate(divided, Map.of("x", 235.0)).status()).isEqualTo("DIVISION_BY_ZERO");
        var overflow = contract(new VariableExpression("MULTIPLY", null, null, VariableExpression.input("x"), VariableExpression.constant(Double.MAX_VALUE)), null, null);
        assertThat(evaluator.evaluate(overflow, Map.of("x", Double.MAX_VALUE)).status()).isEqualTo("NONFINITE_OUTPUT");
    }
    @Test void depthAndNodeBudgetsBoundEvaluation() {
        VariableExpression deep = VariableExpression.input("x");
        for (int index = 0; index < 13; index++) deep = new VariableExpression("ADD", null, null, deep, VariableExpression.constant(0));
        assertThat(evaluator.evaluate(contract(deep, null, null), Map.of("x", 1.0)).status()).isEqualTo("EXPRESSION_LIMIT");
        assertThat(evaluator.evaluate(contract(tree(6), null, null), Map.of("x", 1.0)).status()).isEqualTo("EXPRESSION_LIMIT");
    }
    @Test void explicitTemperatureConversionAndDeclaredInputBoundsAreEnforced() {
        var converted = new VariableContract(UUID.randomUUID(), 1, "fahrenheit", "Fahrenheit", "DERIVED", "F", "v1",
                Map.of("x", new VariableContract.Input("C", -50.0, 150.0)),
                new VariableExpression("CONVERT", null, "F", VariableExpression.input("x"), null), null, null);
        assertThat(evaluator.evaluate(converted, Map.of("x", 100.0)).value()).isEqualTo(212.0);
        assertThat(evaluator.evaluate(converted, Map.of("x", 151.0)).status()).isEqualTo("INPUT_OUT_OF_RANGE");
    }
    private VariableExpression tree(int depth) {
        return depth == 0 ? VariableExpression.input("x") : new VariableExpression("ADD", null, null, tree(depth - 1), tree(depth - 1));
    }
}
