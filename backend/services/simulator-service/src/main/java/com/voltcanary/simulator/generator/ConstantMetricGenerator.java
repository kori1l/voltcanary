package com.voltcanary.simulator.generator;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class ConstantMetricGenerator {

    public double value(double mean, double noise) {
        return mean + ThreadLocalRandom.current().nextDouble(-noise, noise);
    }
}
