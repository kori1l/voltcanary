package com.voltcanary.telemetry.service;

import com.voltcanary.common.business.EnergyMeterDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Physical boundaries shared only by contiguous revisions of one meter/source. */
final class EnergyMeterWindows {

    record Window(EnergyMeterDefinition definition, Instant from, Instant to) {}

    private EnergyMeterWindows() {}

    static List<Window> of(List<EnergyMeterDefinition> history) {
        List<Window> windows = new ArrayList<>();
        int first = 0;
        while (first < history.size()) {
            int last = first;
            while (
                last + 1 < history.size() && contiguous(history.get(last), history.get(last + 1))
            ) {
                last++;
            }
            Instant from = history.get(first).effectiveFrom();
            Instant to = history.get(last).effectiveTo();
            for (int index = first; index <= last; index++) {
                windows.add(new Window(history.get(index), from, to));
            }
            first = last + 1;
        }
        return List.copyOf(windows);
    }

    private static boolean contiguous(EnergyMeterDefinition left, EnergyMeterDefinition right) {
        return (
            left.physicalMeterId().equals(right.physicalMeterId()) &&
            left.sourceId().equals(right.sourceId()) &&
            left.effectiveTo() != null &&
            left.effectiveTo().equals(right.effectiveFrom())
        );
    }
}
