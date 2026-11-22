package com.voltcanary.telemetry.observation;

/** Holds delivery for operator/recovery work. It must never be auto-ACKed as a successful observation. */
public final class ObservationCaptureUnavailable extends RuntimeException {

    public ObservationCaptureUnavailable(String code) {
        super(code);
    }
}
