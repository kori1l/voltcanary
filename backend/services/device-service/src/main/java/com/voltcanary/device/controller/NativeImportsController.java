package com.voltcanary.device.controller;

import com.voltcanary.device.configurable.application.NativeImportWorkflows;
import com.voltcanary.device.configurable.domain.NativeImport;
import com.voltcanary.device.configurable.port.NativeImports;
import com.voltcanary.device.security.AuthenticatedUserResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data")
@RequiredArgsConstructor
public class NativeImportsController {
    private final AuthenticatedUserResolver users;
    private final NativeImportWorkflows imports;

    @PostMapping(value = "/import-artifacts", consumes = "application/octet-stream")
    public NativeImports.Artifact upload(@RequestHeader HttpHeaders headers, @RequestHeader("Idempotency-Key") String key,
            jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        var actor = users.resolve(headers);
        // Bound the read before allocating or decoding an attacker-controlled body.
        return imports.upload(actor, key, request.getInputStream().readNBytes(1_048_577));
    }

    @PostMapping("/datasets/{dataset}/import-reviews")
    public NativeImport validate(@RequestHeader HttpHeaders headers, @PathVariable UUID dataset, @RequestBody byte[] body) {
        return imports.validate(users.resolve(headers), dataset, body);
    }

    @GetMapping("/datasets/{dataset}/import-reviews/{id}")
    public NativeImport inspect(@RequestHeader HttpHeaders headers, @PathVariable UUID dataset, @PathVariable UUID id) {
        return imports.inspect(users.resolve(headers), dataset, id);
    }

    @PostMapping("/datasets/{dataset}/import-reviews/{id}/apply")
    public org.springframework.http.ResponseEntity<NativeImport> apply(@RequestHeader HttpHeaders headers, @PathVariable UUID dataset, @PathVariable UUID id, @RequestBody byte[] body) {
        var result = imports.apply(users.resolve(headers), dataset, id, body);
        return org.springframework.http.ResponseEntity.status(result.status().equals("RUNNING") ? 202 : 200).body(result);
    }

    @GetMapping("/jobs/{id}")
    public NativeImport inspectJob(@RequestHeader HttpHeaders headers, @PathVariable UUID id) {
        return imports.inspectJob(users.resolve(headers), id);
    }

    @PostMapping("/jobs/{id}/advance")
    public NativeImport advance(@RequestHeader HttpHeaders headers, @PathVariable UUID id, @RequestBody byte[] body) {
        return imports.advance(users.resolve(headers), id, body);
    }

    @PostMapping("/jobs/{id}/cancel")
    public NativeImport cancel(@RequestHeader HttpHeaders headers, @PathVariable UUID id, @RequestBody(required = false) byte[] body) {
        return imports.cancel(users.resolve(headers), id, body);
    }

    @GetMapping("/jobs/{id}/chunks")
    public com.fasterxml.jackson.databind.JsonNode chunks(@RequestHeader HttpHeaders headers, @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "-1") int afterChunk,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        return imports.chunks(users.resolve(headers), id, afterChunk, limit);
    }

    @GetMapping("/import-capabilities")
    public java.util.Map<String, Object> capabilities(@RequestHeader HttpHeaders headers) {
        users.resolve(headers);
        return java.util.Map.of("profile", "native-import-2", "modes", java.util.List.of("ATOMIC_INSERT", "ATOMIC_UPSERT", "STAGED_REPLACE", "CHUNKED_UPSERT"),
                "formats", java.util.List.of("CSV", "JSONL"), "maxSourceBytes", 1_048_576, "maxPlanBytes", 16 * 1_048_576,
                "maxAtomicRows", 500, "maxReplacementRows", 500, "maxChunkedRows", 10_000, "chunkRows", 100,
                "references", java.util.Map.of("maxOwners", 32, "maxKeys", 2048, "cardinality", "EXACT_ONE", "owner", "NATIVE"));
    }
}
