package com.voltcanary.telemetry.domain;

public enum QualityFlag {
    OK,
    VALID,
    MISSING_REQUIRED_FIELD,
    INVALID_TIMESTAMP,
    OUT_OF_ORDER,
    INVALID_VALUE,
    DUPLICATE,
    OUT_OF_RANGE,
    NORMALIZED
}
