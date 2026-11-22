package com.voltcanary.device.controller;

import com.voltcanary.common.security.VclWorkerAssertion;
import com.voltcanary.device.config.VclWorkerIdentityProperties;
import com.voltcanary.device.configurable.application.CircleFeatures;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import com.voltcanary.vcl.VclCircleRevision;
import com.voltcanary.vcl.VclException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CircleFeaturesController {

    public record RevisionRequest(long expectedRevision, VclCircleRevision.Definition geometry) {}

    private final CircleFeatures circles;
    private final AuthenticatedUserResolver users;
    private final VclWorkerAssertion verifier;

    public CircleFeaturesController(
        CircleFeatures circles,
        AuthenticatedUserResolver users,
        VclWorkerIdentityProperties identity
    ) {
        this.circles = circles;
        this.users = users;
        verifier = identity.forAudience("device-service");
    }

    @GetMapping("/data/spatial-features/circles")
    public CircleFeatures.Page list(
        @RequestHeader HttpHeaders headers,
        @RequestParam(required = false) UUID after,
        @RequestParam(defaultValue = "25") int limit
    ) {
        return circles.list(users.resolve(headers).id(), after, limit);
    }

    @PutMapping("/data/spatial-features/{id}/circle")
    public VclCircleRevision revise(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestBody RevisionRequest request
    ) {
        return circles.revise(
            users.resolve(headers).id(),
            id,
            request.expectedRevision(),
            request.geometry()
        );
    }

    @GetMapping("/data/spatial-features/{id}/circle")
    public VclCircleRevision describe(
        @RequestHeader HttpHeaders headers,
        @PathVariable UUID id,
        @RequestParam long revision
    ) {
        return circles.describe(users.resolve(headers).id(), null, id, revision);
    }

    @GetMapping("/internal/vcl/spatial-features/{id}/circle")
    public VclCircleRevision machine(
        @RequestHeader(value = VclWorkerSourceController.HEADER, required = false) String assertion,
        @PathVariable UUID id,
        @RequestParam long revision,
        @RequestParam(value = "current", required = false) Boolean current
    ) {
        if (verifier == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        VclWorkerAssertion.Scope scope;
        try {
            String path = "/internal/vcl/spatial-features/" + id + "/circle?revision=" + revision;
            if (current != null) path += "&current=" + current;
            scope = verifier.verify(
                assertion,
                "SPATIAL_CIRCLE",
                id,
                VclWorkerAssertion.requestDigest("GET", path, new byte[0])
            );
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return circles.describe(
            scope.actor(),
            scope.account(),
            id,
            revision,
            Boolean.TRUE.equals(current)
        );
    }

    @ExceptionHandler(VclException.class)
    public ResponseEntity<Map<String, String>> failure(VclException failure) {
        HttpStatus status = switch (failure.code()) {
            case "OWNER_ACCESS_REVOKED" -> HttpStatus.FORBIDDEN;
            case "SPATIAL_FEATURE_UNAVAILABLE" -> HttpStatus.NOT_FOUND;
            case "SPATIAL_REVISION_CHANGED" -> HttpStatus.CONFLICT;
            case "SPATIAL_FEATURE_LIMIT" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", failure.code()));
    }
}
