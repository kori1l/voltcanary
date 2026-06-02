package com.voltcanary.analytics.baseline;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BaselineCalculator {

    public BaselineStats calculate(List<Double> sortedValues) {
        if (sortedValues.isEmpty()) {
            return new BaselineStats(0, 0, 0, 0, 0, 0);
        }
        double sum = sortedValues.stream().mapToDouble(Double::doubleValue).sum();
        return new BaselineStats(
                sum / sortedValues.size(),
                percentile(sortedValues, 0.5),
                percentile(sortedValues, 0.9),
                sortedValues.get(0),
                sortedValues.get(sortedValues.size() - 1),
                sortedValues.size());
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
