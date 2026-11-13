package com.voltcanary.common.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Fixed read routes only; no model-supplied URL, automatic redirect, credential storage or mutation. */
public final class ConfigurableOwnerReads {
    public static final int MAX_BYTES = 1_048_576;
    public static final class Failure extends RuntimeException {
        private final String code;
        private Failure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    private final RestClient client;

    public ConfigurableOwnerReads(RestClient.Builder builder, String baseUrl, Duration timeout) {
        var transport = new ReadFactory();
        transport.setConnectTimeout(timeout);
        transport.setReadTimeout(timeout);
        client = builder.clone().baseUrl(baseUrl).requestFactory(transport).build();
    }

    public byte[] dataset(String authorization, UUID id, long revision, long deadline) {
        return read(authorization, "/data/datasets/{id}?schemaRevision={revision}", id, revision, deadline, "DATASET");
    }

    public byte[] datasetAsOf(String authorization, UUID id, byte[] body, long deadline, boolean split) {
        return datasetRead(authorization, id, body, deadline,
                split ? "/data/datasets/{id}/query-asof-split" : "/data/datasets/{id}/query-asof");
    }

    public byte[] datasetAsOfManifest(String authorization, UUID id, byte[] body, long deadline) {
        return datasetRead(authorization, id, body, deadline, "/data/datasets/{id}/query-asof-manifest");
    }

    private byte[] datasetRead(String authorization, UUID id, byte[] body, long deadline, String path) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) {
            throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        }
        if (id == null || body == null || body.length == 0 || body.length > MAX_BYTES) throw new Failure("OWNER_TARGET_INVALID");
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.post().uri(path, id).header("Authorization", authorization)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure("DATASET_UNAVAILABLE");
                        if (status == 409) throw new Failure("METADATA_SNAPSHOT_CHANGED");
                        if (status != 200) throw new Failure("DATASET_OWNER_UNAVAILABLE");
                        byte[] payload = readBounded(response.getBody(), deadline);
                        if (payload.length > MAX_BYTES) throw new Failure("OWNER_SNAPSHOT_INVALID");
                        return payload;
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("DATASET_OWNER_UNAVAILABLE"); }
    }

    public byte[] sourceBinding(String authorization, UUID id, long revision, long deadline) {
        return read(authorization, "/data/source-bindings/{id}?schemaRevision={revision}", id, revision, deadline, "SOURCE_BINDING");
    }

    public byte[] sourceCapture(String authorization, UUID id, long revision, long deadline) {
        return read(authorization, "/data/source-bindings/{id}/capture?schemaRevision={revision}", id, revision, deadline, "SOURCE_CAPTURE");
    }

    public byte[] workerSourceCapture(String assertion, UUID id, long revision, long deadline) {
        if (assertion == null || assertion.isBlank() || assertion.length() > 4096 || id == null || revision < 1) {
            throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        }
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.get().uri("/internal/vcl/source-bindings/{id}/capture?schemaRevision={revision}", id, revision)
                    .header("X-VCL-Worker-Assertion", assertion).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure("SOURCE_BINDING_UNAVAILABLE");
                        if (status == 409) throw new Failure("METADATA_SNAPSHOT_CHANGED");
                        if (status != 200) throw new Failure("SOURCE_CAPTURE_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("SOURCE_CAPTURE_OWNER_UNAVAILABLE"); }
    }

    public byte[] workerDatasetDescription(String assertion, UUID id, long revision, long deadline) {
        return workerRevision(assertion, id, revision, deadline,
                "/internal/vcl/datasets/{id}?schemaRevision={revision}", "DATASET");
    }

    public byte[] workerCircleRevision(String assertion, UUID id, long revision, long deadline) {
        return workerCircleRevision(assertion, id, revision, deadline, false);
    }

    public byte[] workerCircleRevision(String assertion, UUID id, long revision, long deadline, boolean requireCurrent) {
        return workerRevision(assertion, id, revision, deadline,
                "/internal/vcl/spatial-features/{id}/circle?revision={revision}" + (requireCurrent ? "&current=true" : ""),
                "SPATIAL_FEATURE");
    }

    private byte[] workerRevision(String assertion, UUID id, long revision, long deadline, String path, String kind) {
        if (assertion == null || assertion.isBlank() || assertion.length() > 4096 || id == null || revision < 1) {
            throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        }
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.get().uri(path, id, revision)
                    .header("X-VCL-Worker-Assertion", assertion).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure(kind + "_UNAVAILABLE");
                        if (status == 409) throw new Failure("METADATA_SNAPSHOT_CHANGED");
                        if (status != 200) throw new Failure(kind + "_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure(kind + "_OWNER_UNAVAILABLE"); }
    }

    public byte[] workerGrantDescription(String assertion, UUID id, long epoch, long deadline) {
        if (assertion == null || assertion.isBlank() || assertion.length() > 4096 || id == null || epoch < 1) {
            throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        }
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.get().uri("/internal/vcl/grants/{id}?grantEpoch={epoch}", id, epoch)
                    .header("X-VCL-Worker-Assertion", assertion).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure("AUTOMATION_GRANT_UNAVAILABLE");
                        if (status == 409) throw new Failure("AUTOMATION_GRANT_INACTIVE");
                        if (status != 200) throw new Failure("GRANT_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("GRANT_OWNER_UNAVAILABLE"); }
    }

    public byte[] workerEvidencePolicy(String assertion, UUID id, long deadline) {
        if (assertion == null || assertion.isBlank() || assertion.length() > 4096 || id == null) {
            throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        }
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.get().uri("/internal/vcl/evidence-policies/{id}?revision=1", id)
                    .header("X-VCL-Worker-Assertion", assertion).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure("EVIDENCE_POLICY_UNAVAILABLE");
                        if (status == 409) throw new Failure("EVIDENCE_POLICY_INACTIVE");
                        if (status != 200) throw new Failure("POLICY_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("POLICY_OWNER_UNAVAILABLE"); }
    }

    public byte[] workerDatasetAsOfManifest(String assertion, UUID id, byte[] body, long deadline) {
        return workerDatasetRows(assertion, id, body, deadline, "/internal/vcl/datasets/{id}/query-asof-manifest");
    }

    public byte[] workerWindowAlertEffect(String assertion, UUID id, byte[] body, long deadline) {
        return workerWindowAlert(assertion, id, body, deadline, "");
    }

    public byte[] workerWindowAlertReceipt(String assertion, UUID id, byte[] body, long deadline) {
        return workerWindowAlert(assertion, id, body, deadline, "/receipt");
    }

    public byte[] workerWindowAlertSettlement(String assertion, UUID id, byte[] body, long deadline) {
        return workerWindowAlert(assertion, id, body, deadline, "/settlement");
    }

    private byte[] workerWindowAlert(String assertion, UUID id, byte[] body, long deadline, String suffix) {
        if (assertion == null || assertion.isBlank() || assertion.length() > 4096 || id == null
                || body == null || body.length == 0 || body.length > 8192) throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.post().uri("/internal/vcl/window-alert-proposals/{id}" + suffix, id)
                    .header("X-VCL-Worker-Assertion", assertion)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 409) throw new Failure("ALERT_EFFECT_REFUSED");
                        if (status == 404) throw new Failure("ALERT_EFFECT_RECEIPT_UNAVAILABLE");
                        if (status != 200) throw new Failure("ALERT_EFFECT_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("ALERT_EFFECT_OWNER_UNAVAILABLE"); }
    }

    public byte[] workerDatasetAsOf(String assertion, UUID id, byte[] body, long deadline, boolean split) {
        return workerDatasetRows(assertion, id, body, deadline,
                split ? "/internal/vcl/datasets/{id}/query-asof-split" : "/internal/vcl/datasets/{id}/query-asof");
    }

    private byte[] workerDatasetRows(String assertion, UUID id, byte[] body, long deadline, String path) {
        if (assertion == null || assertion.isBlank() || assertion.length() > 4096 || id == null
                || body == null || body.length == 0 || body.length > MAX_BYTES) {
            throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        }
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.post().uri(path, id).header("X-VCL-Worker-Assertion", assertion)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure("DATASET_UNAVAILABLE");
                        if (status == 409) throw new Failure("METADATA_SNAPSHOT_CHANGED");
                        if (status != 200) throw new Failure("DATASET_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("DATASET_OWNER_UNAVAILABLE"); }
    }

    public byte[] workerSourceObservations(String assertion, UUID id, byte[] body, long deadline) {
        return workerSourcePost(assertion, id, "/internal/vcl/source-bindings/{id}/observations", body, deadline);
    }

    public byte[] workerSourceCoverage(String assertion, UUID id, UUID proof, byte[] body, long deadline) {
        if (proof == null) throw new Failure("OWNER_TARGET_INVALID");
        return workerSourcePost(assertion, id, "/internal/vcl/source-bindings/{id}/coverage/" + proof, body, deadline);
    }

    public byte[] workerSourceCoverageSearch(String assertion, UUID id, byte[] body, long deadline) {
        return workerSourcePost(assertion, id, "/internal/vcl/source-bindings/{id}/coverage-search", body, deadline);
    }

    private byte[] workerSourcePost(String assertion, UUID id, String path, byte[] body, long deadline) {
        if (assertion == null || assertion.isBlank() || assertion.length() > 4096 || id == null
                || body == null || body.length == 0 || body.length > 8192) throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.post().uri(path, id).header("X-VCL-Worker-Assertion", assertion)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure("SOURCE_BINDING_UNAVAILABLE");
                        if (status == 409) throw new Failure("METADATA_SNAPSHOT_CHANGED");
                        if (status == 422) throw new Failure("SOURCE_CAPTURE_UNAVAILABLE");
                        if (status != 200) throw new Failure("SOURCE_CAPTURE_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("SOURCE_CAPTURE_OWNER_UNAVAILABLE"); }
    }

    public byte[] sourceObservations(String authorization, UUID id, byte[] body, long deadline) {
        return sourceObservationRead(authorization, id, body, deadline, "/data/source-bindings/{id}/observations");
    }

    public byte[] sourceCoverage(String authorization, UUID id, UUID proof, byte[] body, long deadline) {
        if (proof == null) throw new Failure("OWNER_TARGET_INVALID");
        return sourceObservationRead(authorization, id, body, deadline, "/data/source-bindings/{id}/coverage/" + proof);
    }

    private byte[] sourceObservationRead(String authorization, UUID id, byte[] body, long deadline, String path) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        if (id == null || body == null || body.length == 0 || body.length > 8192) throw new Failure("OWNER_TARGET_INVALID");
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.post().uri(path, id).header("Authorization", authorization)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure("SOURCE_BINDING_UNAVAILABLE");
                        if (status == 409) throw new Failure("METADATA_SNAPSHOT_CHANGED");
                        if (status == 422) throw new Failure("SOURCE_CAPTURE_UNAVAILABLE");
                        if (status != 200) throw new Failure("SOURCE_CAPTURE_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("SOURCE_CAPTURE_OWNER_UNAVAILABLE"); }
    }

    public byte[] observationReadiness(String internalToken, byte[] schemaPin, long deadline) {
        return internalObservationRead("/internal/observations/binding-readiness", internalToken, schemaPin, deadline, false);
    }

    public byte[] observations(String internalToken, byte[] request, long deadline) {
        return internalObservationRead("/internal/observations/read", internalToken, request, deadline, true);
    }

    public byte[] observationCoverage(String internalToken, UUID proof, byte[] request, long deadline) {
        if (proof == null) throw new Failure("OWNER_TARGET_INVALID");
        return internalObservationRead("/internal/observations/source-coverage/" + proof + "/read",
                internalToken, request, deadline, true);
    }

    public byte[] observationCoverageSearch(String internalToken, byte[] request, long deadline) {
        return internalObservationRead("/internal/observations/source-coverage/find", internalToken, request, deadline, true);
    }

    private byte[] internalObservationRead(String path, String internalToken, byte[] schemaPin, long deadline, boolean page) {
        if (internalToken == null || internalToken.isBlank() || schemaPin == null || schemaPin.length == 0 || schemaPin.length > 8192) {
            throw new Failure("CAPTURE_OWNER_UNAVAILABLE");
        }
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.post().uri(path).header("X-Internal-Service-Token", internalToken)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(schemaPin).exchange((request, response) -> {
                        if (page && response.getStatusCode().value() == 409) throw new Failure("CAPTURE_READ_CONFLICT");
                        if (page && response.getStatusCode().value() == 422) throw new Failure("SOURCE_CAPTURE_UNAVAILABLE");
                        if (response.getStatusCode().value() != 200) throw new Failure("CAPTURE_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("CAPTURE_OWNER_UNAVAILABLE"); }
    }

    public byte[] automationGrant(String authorization, UUID id, long deadline) {
        return read(authorization, "/alert-rules/configurable/grants/{id}", id, 1, deadline, "AUTOMATION_GRANT");
    }

    public byte[] evidencePolicy(String authorization, UUID id, long deadline) {
        return read(authorization, "/alert-rules/configurable/evidence-policies/{id}", id, 1, deadline, "EVIDENCE_POLICY");
    }

    public byte[] confirmedRuleCutover(String authorization, UUID id, long deadline) {
        return read(authorization, "/alert-rules/configurable/cutover-reviews/{id}/confirmed", id, 1,
                deadline, "RULE_CUTOVER_REVIEW");
    }

    public byte[] confirmedRulePause(String authorization, UUID id, long deadline) {
        return read(authorization, "/alert-rules/configurable/pause-reviews/{id}/confirmed", id, 1,
                deadline, "RULE_PAUSE_REVIEW");
    }

    public byte[] accountLifecycle(String internalToken, UUID actor, long deadline) {
        if (internalToken == null || internalToken.isBlank() || actor == null) throw new Failure("ACCOUNT_OWNER_UNAVAILABLE");
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.get().uri("/internal/accounts/users/{id}/lifecycle", actor)
                    .header("X-Internal-Service-Token", internalToken).exchange((request, response) -> {
                        if (response.getStatusCode().value() == 404) throw new Failure("ACCOUNT_UNAVAILABLE");
                        if (response.getStatusCode().value() != 200) throw new Failure("ACCOUNT_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure("ACCOUNT_OWNER_UNAVAILABLE"); }
    }

    private byte[] read(String authorization, String route, UUID id, long revision, long deadline, String kind) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) {
            throw new Failure("OWNER_AUTHENTICATION_REQUIRED");
        }
        if (id == null || revision < 1) throw new Failure("OWNER_TARGET_INVALID");
        if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
        try {
            return client.get().uri(route, id, revision).header("Authorization", authorization)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) throw new Failure("OWNER_ACCESS_REVOKED");
                        if (status == 404) throw new Failure(kind + "_UNAVAILABLE");
                        if (status == 409 && kind.equals("SOURCE_CAPTURE")) throw new Failure("METADATA_SNAPSHOT_CHANGED");
                        if (status != 200) throw new Failure(kind + "_OWNER_UNAVAILABLE");
                        return readBounded(response.getBody(), deadline);
                    });
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException failure) { throw new Failure(kind + "_OWNER_UNAVAILABLE"); }
    }

    public static byte[] readBounded(InputStream input, long deadline) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() <= MAX_BYTES) {
            if (System.nanoTime() - deadline >= 0) throw new Failure("OWNER_SNAPSHOT_EXPIRED");
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - output.size()));
            if (count == -1) break;
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final class ReadFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
