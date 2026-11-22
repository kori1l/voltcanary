package com.voltcanary.device.controller;

import com.voltcanary.vcl.VclException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    assignableTypes = {
        DatasetsController.class,
        InternalDatasetsController.class,
        NativeCollectionReviewsController.class,
        NativeRowsController.class,
        NativeImportsController.class,
        NativeMigrationsController.class,
        SourceBindingsController.class,
        NativeSharingController.class,
        NativeSharedRowsController.class,
        NativeHistoryController.class,
        NativeSharedHistoryController.class,
        VclWorkerSourceController.class,
        VclWorkerDatasetRowsController.class,
    }
)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class DatasetErrors {

    public record Error(
        String code,
        String stage,
        String path,
        boolean retryable,
        String correlationId,
        Map<String, Object> details
    ) {}

    @ExceptionHandler(VclException.class)
    public ResponseEntity<Error> handle(VclException failure) {
        HttpStatus status = switch (failure.code()) {
            case
                "INVALID_JSON",
                "INVALID_UTF8",
                "INVALID_UNICODE",
                "PAYLOAD_SIZE",
                "UNSAFE_JSON_NUMBER",
                "GRAMMAR",
                "REQUEST_FIELDS_INVALID",
                "HISTORY_REQUEST_INVALID",
                "JOB_REVISION_INVALID",
                "FIELD_KEYS_INVALID",
                "RESERVATION_ID_INVALID",
                "DOCUMENT_REQUIRED",
                "REVIEW_REVISION_INVALID",
                "REVIEW_DIGEST_INVALID",
                "IDEMPOTENCY_KEY_INVALID",
                "TARGET_INVALID",
                "CURSOR_INVALID",
                "BINDING_REVISION_INVALID",
                "OBSERVATION_READ_INVALID" -> HttpStatus.BAD_REQUEST;
            case
                "HISTORY_LEASE_UNAVAILABLE",
                "HISTORY_GENERATION_UNAVAILABLE",
                "HISTORY_REVISION_UNAVAILABLE",
                "NATIVE_GRANT_UNAVAILABLE",
                "JOB_UNAVAILABLE",
                "ARTIFACT_UNAVAILABLE",
                "IMPORT_UNAVAILABLE",
                "DATASET_UNAVAILABLE",
                "REFERENCE_TARGET_UNAVAILABLE",
                "REVIEW_UNAVAILABLE",
                "ROW_UNAVAILABLE",
                "SOURCE_BINDING_UNAVAILABLE",
                "SNAPSHOT_UNAVAILABLE" -> HttpStatus.NOT_FOUND;
            case "DATA_ACCESS_DENIED", "ACCOUNT_ACCESS_REVOKED" -> HttpStatus.FORBIDDEN;
            case "SNAPSHOT_EXPIRED" -> HttpStatus.GONE;
            case
                "ACCOUNT_SETUP_REQUIRED",
                "ACCESS_SCOPE_CHANGED",
                "DATASET_ID_REUSED",
                "REVIEW_CONFLICT",
                "REVIEW_EXPIRED",
                "REVIEW_STATE_CONFLICT",
                "IDEMPOTENCY_KEY_REUSED",
                "ROW_CONFLICT",
                "TARGET_CONFLICT",
                "CURSOR_CONFLICT",
                "CURSOR_SCOPE_CHANGED",
                "CURSOR_EXPIRED",
                "ROW_REFERENCED",
                "ROW_IDENTITY_CONFLICT",
                "BINDING_REVISION_CHANGED",
                "METADATA_SNAPSHOT_CHANGED",
                "CAPTURE_READ_CONFLICT",
                "SNAPSHOT_CONFLICT",
                "HISTORY_CUT_UNAVAILABLE",
                "HISTORY_BASELINE_REQUIRED",
                "SCHEMA_REVISION_CONFLICT",
                "JOB_CONFLICT",
                "JOB_STATE_CONFLICT",
                "VALIDATION_STALE",
                "ARTIFACT_EXPIRED",
                "ASOF_PROJECTION_CONFLICT",
                "MAPPING_REVIEW_CONFLICT",
                "MAPPING_UNCHANGED" -> HttpStatus.CONFLICT;
            case
                "HISTORY_LEASE_QUOTA",
                "HISTORY_BUDGET_EXCEEDED",
                "HISTORY_BASELINE_LIMIT",
                "NATIVE_GRANT_QUOTA",
                "IMPORT_QUOTA",
                "DATASET_QUOTA",
                "REVIEW_QUOTA",
                "QUERY_BUDGET_EXCEEDED",
                "SNAPSHOT_QUOTA",
                "SNAPSHOT_BUDGET_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case
                "NATIVE_STORAGE_UNAVAILABLE",
                "NATIVE_CATALOG_DRIFT",
                "ROW_COMMIT_UNCERTAIN",
                "BINDING_METADATA_STALE",
                "BINDING_SNAPSHOT_INTEGRITY",
                "MAPPING_RECEIPT_INTEGRITY",
                "HISTORY_INTEGRITY",
                "NATIVE_GRANT_INTEGRITY",
                "WORKSPACE_AUTHORITY_UNAVAILABLE",
                "MIGRATION_INTEGRITY",
                "DEPENDENCY_OWNER_UNAVAILABLE",
                "IMPORT_INTEGRITY",
                "ARTIFACT_INTEGRITY",
                "CAPTURE_OWNER_UNAVAILABLE",
                "OWNER_SNAPSHOT_INVALID",
                "OWNER_SNAPSHOT_EXPIRED",
                "SNAPSHOT_INTEGRITY" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status).body(
            new Error(
                failure.code(),
                "DATASET_OWNER",
                failure.path(),
                status == HttpStatus.SERVICE_UNAVAILABLE,
                null,
                Map.of()
            )
        );
    }
}
