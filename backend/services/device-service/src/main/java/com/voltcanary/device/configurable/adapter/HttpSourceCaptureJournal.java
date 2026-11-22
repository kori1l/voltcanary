package com.voltcanary.device.configurable.adapter;

import com.voltcanary.common.web.ConfigurableOwnerReads;
import com.voltcanary.common.web.JacksonRestClients;
import com.voltcanary.device.config.SourceCaptureProperties;
import com.voltcanary.device.configurable.port.SourceCaptureJournal;
import com.voltcanary.vcl.VclException;
import com.voltcanary.vcl.VclObservationReadiness;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class HttpSourceCaptureJournal implements SourceCaptureJournal {

    private final ConfigurableOwnerReads reads;
    private final SourceCaptureProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    public HttpSourceCaptureJournal(SourceCaptureProperties properties) {
        this.properties = properties;
        reads = new ConfigurableOwnerReads(
            JacksonRestClients.builder(),
            properties.getTelemetryServiceBaseUrl(),
            properties.getTimeout()
        );
    }

    @Override
    public VclObservationReadiness read(VclObservationReadiness.Pin pin) {
        try {
            byte[] request = mapper.writeValueAsBytes(pin);
            var value = VclObservationReadiness.parse(
                reads.observationReadiness(
                    properties.getInternalServiceToken(),
                    request,
                    System.nanoTime() + Duration.ofSeconds(10).toNanos()
                )
            );
            if (!pin.equals(value.pin())) throw new VclException("OWNER_SNAPSHOT_INVALID", "");
            return value;
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        } catch (java.io.IOException failure) {
            throw new VclException("CAPTURE_OWNER_UNAVAILABLE", "");
        }
    }

    @Override
    public com.voltcanary.vcl.VclObservationPage page(
        com.voltcanary.vcl.VclObservationRead request
    ) {
        try {
            var value = com.voltcanary.vcl.VclObservationPage.parse(
                reads.observations(
                    properties.getInternalServiceToken(),
                    request.bytes(),
                    System.nanoTime() + Duration.ofSeconds(10).toNanos()
                )
            );
            if (!request.equals(value.request())) throw new VclException(
                "OWNER_SNAPSHOT_INVALID",
                ""
            );
            return value;
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }

    @Override
    public com.voltcanary.vcl.VclSourceCoverage coverage(
        VclObservationReadiness.Pin pin,
        java.util.UUID proof
    ) {
        try {
            return com.voltcanary.vcl.VclSourceCoverage.parse(
                reads.observationCoverage(
                    properties.getInternalServiceToken(),
                    proof,
                    mapper.writeValueAsBytes(pin),
                    System.nanoTime() + Duration.ofSeconds(10).toNanos()
                )
            );
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        } catch (java.io.IOException failure) {
            throw new VclException("CAPTURE_OWNER_UNAVAILABLE", "");
        }
    }

    @Override
    public java.util.UUID findQualifiedCoverage(com.voltcanary.vcl.VclCoverageSearch request) {
        try {
            return com.voltcanary.vcl.VclCoverageSearch.parseResult(
                reads.observationCoverageSearch(
                    properties.getInternalServiceToken(),
                    request.bytes(),
                    System.nanoTime() + Duration.ofSeconds(10).toNanos()
                )
            );
        } catch (ConfigurableOwnerReads.Failure failure) {
            throw new VclException(failure.code(), "");
        }
    }
}
