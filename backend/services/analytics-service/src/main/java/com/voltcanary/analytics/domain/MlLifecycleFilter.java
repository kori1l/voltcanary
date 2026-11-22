package com.voltcanary.analytics.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum MlLifecycleFilter {
    DEFAULT,
    ACTIVE,
    ALL,
    FAILED,
    ARCHIVED,
    DELETED;

    public static MlLifecycleFilter parse(String value) {
        return value == null || value.isBlank()
            ? DEFAULT
            : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public List<MlTrainingRunStatus> trainingStatuses() {
        return Arrays.stream(MlTrainingRunStatus.values())
            .filter(
                status ->
                    switch (this) {
                        case ALL -> true;
                        case FAILED -> status == MlTrainingRunStatus.FAILED ||
                            status == MlTrainingRunStatus.CANCELED;
                        case ARCHIVED -> status == MlTrainingRunStatus.ARCHIVED;
                        case DELETED -> status == MlTrainingRunStatus.DELETED;
                        case DEFAULT, ACTIVE -> status != MlTrainingRunStatus.ARCHIVED &&
                            status != MlTrainingRunStatus.DELETED;
                    }
            )
            .toList();
    }
}
