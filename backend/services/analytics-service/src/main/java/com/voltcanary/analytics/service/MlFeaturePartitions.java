package com.voltcanary.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltcanary.analytics.domain.MlDatasetVersion;
import com.voltcanary.analytics.domain.MlTrainingSplitStrategy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Stable partitions of observations, keeping devices/timestamps together as requested. */
public final class MlFeaturePartitions {
    private MlFeaturePartitions() { }

    public static <T> Map<UUID, String> assign(MlDatasetVersion dataset, List<T> rows,
            Function<T, UUID> id, Function<T, Instant> timestamp, Function<T, String> device) {
        double train = 0.7, validation = 0.15;
        long seed = 42;
        try {
            if (dataset.getSplitConfigJson() != null && !dataset.getSplitConfigJson().isBlank()) {
                var config = new ObjectMapper().readTree(dataset.getSplitConfigJson());
                train = config.path("train").asDouble(0.7);
                validation = config.path("validation").asDouble(0.15);
                double test = config.path("test").asDouble(0.15);
                if (!Double.isFinite(train + validation + test) || train <= 0 || validation < 0 || test < 0
                        || Math.abs(train + validation + test - 1) > 0.000001) throw new IllegalArgumentException("Invalid dataset split weights");
                seed = config.path("seed").asLong(42);
            }
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid dataset split configuration", invalid); }
        var strategy = dataset.getSplitStrategy() == null ? MlTrainingSplitStrategy.TIME_BASED : dataset.getSplitStrategy();
        var times = rows.stream().map(timestamp).distinct().sorted().toList();
        Map<Instant, Integer> ranks = new HashMap<>();
        for (int index = 0; index < times.size(); index++) ranks.put(times.get(index), index);
        Map<UUID, String> assignments = new HashMap<>();
        for (T row : rows) {
            String entity = device.apply(row);
            double fraction = switch (strategy) {
                case TIME_BASED -> (double) ranks.get(timestamp.apply(row)) / times.size();
                case DEVICE_BASED -> bucket(seed + "|" + entity);
                case RANDOM -> bucket(seed + "|" + entity + "|" + timestamp.apply(row));
            };
            assignments.put(id.apply(row), fraction < train ? "TRAIN" : fraction < train + validation ? "VALIDATION" : "TEST");
        }
        return assignments;
    }

    private static double bucket(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            long value = java.nio.ByteBuffer.wrap(digest).getLong() >>> 11;
            return value * 0x1.0p-53;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
