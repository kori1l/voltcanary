package com.voltcanary.telemetry.controller;

import com.voltcanary.telemetry.observation.ObservationReadiness;
import com.voltcanary.telemetry.observation.SourcePositionProgress;
import com.voltcanary.vcl.VclObservationReadiness;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ObservationReadinessController {
    private final ObservationReadiness readiness;
    private final String token;
    private final com.voltcanary.telemetry.observation.ObservationReads reads;
    private final SourcePositionProgress progress;
    public ObservationReadinessController(ObservationReadiness readiness, com.voltcanary.telemetry.config.ObservationOwnerProperties properties,
            com.voltcanary.telemetry.observation.ObservationReads reads, SourcePositionProgress progress) {
        this.readiness = readiness;
        this.token = properties.getServiceToken();
        this.reads = reads;
        this.progress = progress;
    }

    @PostMapping("/internal/observations/source-progress")
    public SourcePositionProgress.Evidence progress(
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String actual,
            @RequestParam UUID producerEpoch, @RequestBody byte[] body) {
        requireToken(actual);
        VclObservationReadiness.Pin pin;
        try { pin = VclObservationReadiness.parsePin(body); }
        catch (com.voltcanary.vcl.VclException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.code()); }
        try { return progress.read(pin, producerEpoch); }
        catch (com.voltcanary.telemetry.observation.ObservationCaptureUnavailable unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage());
        }
    }
    @PostMapping("/internal/observations/binding-readiness")
    public VclObservationReadiness read(@RequestHeader(value = "X-Internal-Service-Token", required = false) String actual, @RequestBody byte[] body) {
        requireToken(actual);
        VclObservationReadiness.Pin pin;
        try { pin = VclObservationReadiness.parsePin(body); }
        catch (com.voltcanary.vcl.VclException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.code()); }
        try { return readiness.read(pin); }
        catch (com.voltcanary.telemetry.observation.ObservationCaptureUnavailable unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage());
        }
        catch (org.springframework.dao.DataAccessException | com.voltcanary.vcl.VclException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CAPTURE_OWNER_UNAVAILABLE");
        }
    }

    @PostMapping("/internal/observations/read")
    public com.voltcanary.vcl.VclObservationPage observations(@RequestHeader(value = "X-Internal-Service-Token", required = false) String actual, @RequestBody byte[] body) {
        requireToken(actual);
        com.voltcanary.vcl.VclObservationRead request;
        try { request = com.voltcanary.vcl.VclObservationRead.parse(body); }
        catch (com.voltcanary.vcl.VclException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.code()); }
        try { return reads.read(request); }
        catch (com.voltcanary.telemetry.observation.ObservationCaptureUnavailable unavailable) {
            HttpStatus status = switch (unavailable.getMessage()) {
                case "OBSERVATION_READ_CONFLICT" -> HttpStatus.CONFLICT;
                case "SOURCE_CAPTURE_UNAVAILABLE", "OBSERVATION_INTEGRITY_UNAVAILABLE" -> HttpStatus.UNPROCESSABLE_CONTENT;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ResponseStatusException(status, status == HttpStatus.SERVICE_UNAVAILABLE ? "CAPTURE_OWNER_UNAVAILABLE" : unavailable.getMessage());
        }
        catch (org.springframework.dao.DataAccessException | com.voltcanary.vcl.VclException | java.time.DateTimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CAPTURE_OWNER_UNAVAILABLE");
        }
    }

    private void requireToken(String actual) {
        if (token.isBlank() || actual == null || !java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Internal service token required");
        }
    }
}
