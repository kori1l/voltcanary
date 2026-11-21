package com.voltcanary.device.configurable.port;

import com.voltcanary.device.configurable.domain.NativeImport;
import com.voltcanary.vcl.VclDocument;
import java.time.Instant;
import java.util.UUID;

public interface NativeImports {
    record Artifact(UUID id, String sha256, int bytes, Instant expiresAt) { }
    Artifact upload(UUID account, UUID actor, String key, byte[] bytes);
    NativeImport validate(UUID account, UUID actor, UUID dataset, String key, VclDocument document);
    NativeImport inspect(UUID account, UUID actor, UUID dataset, UUID id);
    NativeImport apply(UUID account, UUID actor, UUID dataset, UUID id, String digest, String key);
    NativeImport inspectJob(UUID account, UUID actor, UUID id);
    NativeImport advance(UUID account, UUID actor, UUID id, long expectedRevision);
    NativeImport cancel(UUID account, UUID actor, UUID id);
    com.fasterxml.jackson.databind.JsonNode chunks(UUID account, UUID actor, UUID id, int afterChunk, int limit);
}
