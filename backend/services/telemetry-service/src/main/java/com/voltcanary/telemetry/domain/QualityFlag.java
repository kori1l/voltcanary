package com.voltcanary.telemetry.domain;

public enum QualityFlag {
    VALID,
    MISSING_REQUIRED_FIELD,
    INVALID_TIMESTAMP,
    DUPLICATE,
    OUT_OF_RANGE,
    NORMALIZED
}
