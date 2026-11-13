package com.voltcanary.device.configurable.port;

import com.voltcanary.vcl.VclObservationReadiness;

public interface SourceCaptureJournal {
    VclObservationReadiness read(VclObservationReadiness.Pin pin);
    com.voltcanary.vcl.VclObservationPage page(com.voltcanary.vcl.VclObservationRead request);
    com.voltcanary.vcl.VclSourceCoverage coverage(VclObservationReadiness.Pin pin, java.util.UUID proof);
    default java.util.UUID findQualifiedCoverage(com.voltcanary.vcl.VclCoverageSearch request) {
        throw new com.voltcanary.vcl.VclException("SOURCE_CAPTURE_UNAVAILABLE", "");
    }
}
