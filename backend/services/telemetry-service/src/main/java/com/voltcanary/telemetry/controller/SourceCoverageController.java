package com.voltcanary.telemetry.controller;

import com.voltcanary.common.events.telemetry.SourcePosition;
import com.voltcanary.telemetry.config.ObservationOwnerProperties;
import com.voltcanary.telemetry.observation.ObservationCaptureUnavailable;
import com.voltcanary.telemetry.observation.SourceCoverageAttestations;
import com.voltcanary.vcl.VclObservationReadiness;
import com.voltcanary.vcl.VclParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/observations/source-coverage")
@RequiredArgsConstructor
public class SourceCoverageController {
    private final SourceCoverageAttestations coverage;
    private final ObservationOwnerProperties properties;
    private final VclParser parser = new VclParser();

    @PostMapping
    public SourceCoverageAttestations.Result attest(
            @RequestHeader(value = "X-Coverage-Attestor-Token", required = false) String token, @RequestBody byte[] body) {
        String expected = properties.getCoverageAttestorToken();
        if (expected.equals(properties.getServiceToken())) unauthorized();
        requireToken(expected, token);
        VclObservationReadiness.Pin pin;
        SourceCoverageAttestations.Proof proof;
        try {
            if (body.length > 8192) throw new IllegalArgumentException();
            var json = parser.readJson(body);
            if (!json.isObject() || !(json.size() == 7 && !json.has("eventTimeBasis")
                    || json.size() == 8 && json.path("eventTimeBasis").isTextual())) throw new IllegalArgumentException();
            pin = VclObservationReadiness.parsePin(json.path("pin").toString().getBytes(StandardCharsets.UTF_8));
            proof = new SourceCoverageAttestations.Proof(UUID.fromString(json.path("proofId").asText()),
                    UUID.fromString(json.path("inputGeneration").asText()),
                    new SourcePosition(UUID.fromString(json.path("producerEpoch").asText()), json.path("throughSequence").textValue()),
                    Instant.parse(json.path("from").asText()), Instant.parse(json.path("until").asText()),
                    json.path("eventTimeBasis").textValue());
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SOURCE_COVERAGE_PROOF_INVALID");
        }
        try {
            return coverage.attest(pin, proof);
        } catch (ObservationCaptureUnavailable unavailable) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, unavailable.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_COVERAGE_CONFLICT");
        }
    }

    @PostMapping("/{proofId}/read")
    public SourceCoverageAttestations.Result read(@PathVariable UUID proofId,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String token, @RequestBody byte[] body) {
        requireToken(properties.getServiceToken(), token);
        VclObservationReadiness.Pin pin;
        try { pin = VclObservationReadiness.parsePin(body); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SOURCE_COVERAGE_PROOF_INVALID"); }
        return coverage.read(pin, proofId);
    }

    @PostMapping("/find")
    public com.fasterxml.jackson.databind.JsonNode find(
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String token, @RequestBody byte[] body) {
        requireToken(properties.getServiceToken(), token);
        var id = coverage.findQualified(com.voltcanary.vcl.VclCoverageSearch.parse(body));
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (id == null) result.putNull("proofId");
        else result.put("proofId", id.toString());
        return result;
    }

    private static void requireToken(String expected, String actual) {
        if (expected.isBlank() || actual == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) unauthorized();
    }

    private static void unauthorized() {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
