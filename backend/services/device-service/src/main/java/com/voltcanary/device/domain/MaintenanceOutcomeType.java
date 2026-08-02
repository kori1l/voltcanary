package com.voltcanary.device.domain;

public enum MaintenanceOutcomeType {
    CONFIRMED_FAILURE,
    CORRECTIVE_MAINTENANCE,
    PREVENTIVE_MAINTENANCE,
    INSPECTION_NORMAL,
    FALSE_POSITIVE,
    SENSOR_FAULT,
    UNRESOLVED_ANOMALY,
    UNKNOWN
}
