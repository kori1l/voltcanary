package com.voltcanary.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import com.voltcanary.analytics.service.MlFeaturePartitions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MlFeaturePartitionsTest {

    private record Row(UUID id, Instant timestamp, String device) {}

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (int device = 0; device < 100; device++) {
            for (int time = 0; time < 20; time++) {
                rows.add(
                    new Row(UUID.randomUUID(), Instant.EPOCH.plusSeconds(time), "device-" + device)
                );
            }
        }
        return rows;
    }

    @Test
    void timeBasedSplitsKeepTimestampsTogetherAndRespectConfiguredWeights() {
        var dataset = new MlDatasetVersion();
        dataset.setSplitStrategy(MlTrainingSplitStrategy.TIME_BASED);
        dataset.setSplitConfigJson("{\"train\":0.5,\"validation\":0.25,\"test\":0.25}");
        var rows = rows();
        var assignments = MlFeaturePartitions.assign(
            dataset,
            rows,
            Row::id,
            Row::timestamp,
            Row::device
        );
        assertThat(rows).allSatisfy(row ->
            assertThat(assignments.get(row.id())).isEqualTo(
                row.timestamp().getEpochSecond() < 10
                    ? "TRAIN"
                    : row.timestamp().getEpochSecond() < 15
                      ? "VALIDATION"
                      : "TEST"
            )
        );
    }

    @Test
    void deviceBasedSplitsNeverPutOneDeviceIntoMultiplePartitions() {
        var dataset = new MlDatasetVersion();
        dataset.setSplitStrategy(MlTrainingSplitStrategy.DEVICE_BASED);
        var rows = rows();
        var assignments = MlFeaturePartitions.assign(
            dataset,
            rows,
            Row::id,
            Row::timestamp,
            Row::device
        );
        for (int device = 0; device < 100; device++) {
            String name = "device-" + device;
            assertThat(
                rows
                    .stream()
                    .filter(row -> row.device().equals(name))
                    .map(row -> assignments.get(row.id()))
                    .distinct()
            ).hasSize(1);
        }
        assertThat(assignments.values()).contains("TRAIN", "VALIDATION", "TEST");
    }

    @Test
    void randomSplitsAreStableAcrossInputOrderAndUseTheConfiguredSeed() {
        var dataset = new MlDatasetVersion();
        dataset.setSplitStrategy(MlTrainingSplitStrategy.RANDOM);
        var rows = rows();
        var first = MlFeaturePartitions.assign(dataset, rows, Row::id, Row::timestamp, Row::device);
        Collections.reverse(rows);
        assertThat(
            MlFeaturePartitions.assign(dataset, rows, Row::id, Row::timestamp, Row::device)
        ).isEqualTo(first);
        dataset.setSplitConfigJson(
            "{\"train\":0.7,\"validation\":0.15,\"test\":0.15,\"seed\":123}"
        );
        assertThat(
            MlFeaturePartitions.assign(dataset, rows, Row::id, Row::timestamp, Row::device)
        ).isNotEqualTo(first);
    }
}
