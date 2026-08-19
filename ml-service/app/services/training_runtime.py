import hashlib
import json
import sys
import time
from uuid import uuid4

from app.models.failure_evaluation import FailurePredictionEvaluator
from app.models.failure_prediction_model import FailurePredictionModel
from app.models.failure_target import FailureTargetBuilder
from app.models.tabular_anomaly_model import TabularAnomalyModel
from app.schemas.training import ApprovalGateProfile, TrainingRequest, TrainingRunResponse
from app.services.artifact_store import ArtifactStore
from app.services.feature_service import FeatureService
from app.services.model_registry_service import model_registry_service


class IdempotencyConflictError(Exception):
    pass


class TrainingCanceledError(Exception):
    pass


class TrainingTimeoutError(Exception):
    pass


class TrainingRunCoordinator:
    def __init__(self) -> None:
        self.runs: dict[str, TrainingRunResponse] = {}
        self.idempotency_records: dict[str, tuple[str, TrainingRunResponse]] = {}

    def run_id(self, request: TrainingRequest) -> str:
        return request.training_run_id or str(uuid4())

    def fingerprint(self, request: TrainingRequest) -> str:
        payload = request.model_dump(mode="json", by_alias=True)
        payload.pop("cancellationRequested", None)
        payload.pop("trainingTimeoutSeconds", None)
        serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return "sha256:" + hashlib.sha256(serialized.encode("utf-8")).hexdigest()

    def idempotent_result(self, request: TrainingRequest, fingerprint: str) -> TrainingRunResponse | None:
        if not request.idempotency_key:
            return None
        existing = self.idempotency_records.get(request.idempotency_key)
        if existing is None:
            return None
        existing_fingerprint, existing_response = existing
        if existing_fingerprint != fingerprint:
            raise IdempotencyConflictError("training idempotency key was reused with different request metadata")
        return existing_response

    def record_result(
        self,
        request: TrainingRequest,
        run: TrainingRunResponse,
        fingerprint: str,
    ) -> TrainingRunResponse:
        self.runs[run.run_id] = run
        if request.idempotency_key:
            self.idempotency_records[request.idempotency_key] = (fingerprint, run)
        return run

    def get_run(self, run_id: str) -> TrainingRunResponse:
        return self.runs.get(
            run_id,
            TrainingRunResponse(
                runId=run_id,
                status="NOT_FOUND",
                datasetVersionId="unknown",
                failureReason="training run was not found",
            ),
        )

    def artifact_bundle_id(self, run_id: str, request: TrainingRequest) -> str:
        return request.artifact_bundle_id or f"model-{run_id}"

    def checkpoint(self, request: TrainingRequest, started_at: float) -> None:
        if request.cancellation_requested:
            raise TrainingCanceledError()
        timeout = request.training_timeout_seconds
        if timeout is not None and time.monotonic() - started_at > timeout:
            raise TrainingTimeoutError()

    def empty_dataset_response(self, run_id: str, request: TrainingRequest) -> TrainingRunResponse:
        return TrainingRunResponse(
            runId=run_id,
            status="FAILED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=self.artifact_bundle_id(run_id, request),
            failureReason="training requires at least one metric row",
            logsSummary=["Rejected empty training dataset."],
        )

    def canceled_response(self, run_id: str, request: TrainingRequest) -> TrainingRunResponse:
        return TrainingRunResponse(
            runId=run_id,
            status="CANCELED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=self.artifact_bundle_id(run_id, request),
            failureReason="training run cancellation was requested",
            logsSummary=["Training canceled before artifact promotion."],
        )

    def timeout_response(self, run_id: str, request: TrainingRequest) -> TrainingRunResponse:
        return TrainingRunResponse(
            runId=run_id,
            status="FAILED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=self.artifact_bundle_id(run_id, request),
            failureReason="training timeout exceeded before artifact promotion",
            logsSummary=["Training stopped before artifact promotion because the timeout budget was exceeded."],
        )


class ModelArtifactPublisher:
    def __init__(self, artifact_store: ArtifactStore) -> None:
        self.artifact_store = artifact_store

    def save_verified_bundle(
        self,
        artifact_bundle_id: str,
        model_payload: dict,
        manifest_payload: dict,
        supporting_files: dict,
    ) -> dict:
        try:
            bundle = self.artifact_store.save_model_bundle(
                artifact_bundle_id,
                model_payload,
                manifest_payload,
                supporting_files=supporting_files,
            )
            self.artifact_store.verify_model_bundle(bundle["manifestUri"], bundle["artifactHash"])
            return bundle
        except Exception:
            self.artifact_store.delete_artifact_bundle(artifact_bundle_id)
            raise

    def manifest(
        self,
        model_version_id: str,
        run_id: str,
        request: TrainingRequest,
        snapshot=None,
        metrics: dict[str, float] | None = None,
        feature_schema_hash: str | None = None,
        algorithm: str | None = None,
        evaluation_metadata: dict | None = None,
        approval_metadata: dict | None = None,
    ) -> dict:
        resolved_feature_schema_hash = feature_schema_hash or snapshot.feature_schema_hash
        return {
            "artifactContractVersion": (
                FailurePredictionModel.artifact_contract_version
                if request.model_family.upper() == "FAILURE_PREDICTION"
                else "tabular-anomaly-artifact-v1"
            ),
            "modelVersionId": model_version_id,
            "modelFamily": request.model_family,
            "algorithm": algorithm or request.algorithm,
            "featureSchemaHash": resolved_feature_schema_hash,
            "featureConfigVersion": "feature-vector-contract-v1",
            "trainingRunId": run_id,
            "datasetVersionId": request.dataset_version_id,
            "runtime": {
                "python": sys.version.split()[0],
                "service": "voltcanary-ml-service",
                "dependencies": {
                    "numpy": "2.2.6",
                    "scikit-learn": "1.7.1",
                    "joblib": "1.5.2",
                },
            },
            "supportedMetrics": sorted({row.metric for row in request.rows}),
            "supportedDeviceTypes": sorted(
                {str(row.metadata.get("deviceType")) for row in request.rows if row.metadata.get("deviceType")}
            ),
            "metrics": metrics or {},
            "evaluation": evaluation_metadata or {},
            "approvalGates": approval_metadata or {},
            "inferenceInputContract": {
                "modelVersionId": model_version_id,
                "requiredFields": ["deviceId", "metrics"],
                "featureSchemaHash": resolved_feature_schema_hash,
            },
        }

    def feature_schema_hash(self, request: TrainingRequest) -> str:
        for row in request.rows:
            value = row.metadata.get("featureSchemaHash")
            if value:
                return str(value)
        metrics = ",".join(sorted({row.metric for row in request.rows}))
        return f"inline:{request.model_family}:{request.failure_horizon_days}:{metrics}"


class AnomalyTrainingRunner:
    def __init__(
        self,
        feature_service: FeatureService,
        publisher: ModelArtifactPublisher,
        coordinator: TrainingRunCoordinator,
    ) -> None:
        self.feature_service = feature_service
        self.publisher = publisher
        self.coordinator = coordinator

    def run(self, run_id: str, request: TrainingRequest, started_at: float) -> TrainingRunResponse:
        self.coordinator.checkpoint(request, started_at)
        snapshot = self.feature_service.generate_snapshot(request.dataset_version_id, request.rows)
        self.coordinator.checkpoint(request, started_at)
        feature_artifact = self.feature_service.load_snapshot_artifact(snapshot.artifact_uri)
        model = TabularAnomalyModel()
        model.fit(request.rows, feature_artifact)
        self.coordinator.checkpoint(request, started_at)
        metrics = self._evaluate(model, request.rows)
        model_version_id = str(uuid4())
        algorithm = self._algorithm(request, model.algorithm)
        model_version = f"{algorithm}-{run_id[:8]}"
        model_name = f"trained-{request.dataset_version_id}"
        model_payload = {
            "name": model_name,
            "version": model_version,
            "modelVersionId": model_version_id,
            **model.model_payload(),
            "algorithm": algorithm,
            "datasetVersionId": request.dataset_version_id,
            "featureSchemaHash": snapshot.feature_schema_hash,
            "metrics": metrics,
        }
        preprocessor_payload = model.preprocessor_payload(request.dataset_version_id, feature_artifact)
        artifact_bundle_id = self.coordinator.artifact_bundle_id(run_id, request)
        bundle = self.publisher.save_verified_bundle(
            artifact_bundle_id,
            model_payload,
            self.publisher.manifest(
                model_version_id=model_version_id,
                run_id=run_id,
                request=request,
                snapshot=snapshot,
                metrics=metrics,
            ),
            supporting_files={
                "model.joblib": model.serialized_pipeline(),
                "preprocessor.json": preprocessor_payload,
            },
        )
        self.coordinator.checkpoint(request, started_at)
        model_registry_service.register_model(
            {
                "name": model_name,
                "version": model_version,
                "modelVersionId": model_version_id,
                "status": "READY",
                "modelType": model_payload["modelType"],
                "artifactUri": bundle["artifactUri"],
                "manifestUri": bundle["manifestUri"],
                "artifactHash": bundle["artifactHash"],
                "artifactSizeBytes": bundle["artifactSizeBytes"],
                "metrics": metrics,
            }
        )
        return TrainingRunResponse(
            runId=run_id,
            status="SUCCEEDED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=artifact_bundle_id,
            modelVersionId=model_version_id,
            modelVersion=model_version,
            artifactUri=bundle["artifactUri"],
            manifestUri=bundle["manifestUri"],
            artifactHash=bundle["artifactHash"],
            artifactSizeBytes=bundle["artifactSizeBytes"],
            metrics=metrics,
            featureSnapshot=snapshot,
            logsSummary=[
                f"Loaded feature artifact {snapshot.artifact_uri}.",
                f"Registered model {model_name}.",
            ],
        )

    def _evaluate(self, model: TabularAnomalyModel, rows: list) -> dict[str, float]:
        values = {row.metric: float(row.value) for row in rows[-min(3, len(rows)) :]}
        score = model.score(values)["anomalyScore"]
        labels = [row.label for row in rows if row.label]
        label_coverage = len(labels) / len(rows)
        return {
            "validationAnomalyScore": round(score, 6),
            "labelCoverage": round(label_coverage, 6),
            "trainingRows": float(len(rows)),
        }

    def _algorithm(self, request: TrainingRequest, default_algorithm: str) -> str:
        if request.model_family.upper() == "FAILURE_PREDICTION" and request.algorithm == "baseline-statistical-anomaly-v1":
            return default_algorithm
        return request.algorithm or default_algorithm


class ApprovalGateEvaluator:
    def failure_reason(self, request: TrainingRequest, metrics: dict[str, float]) -> str | None:
        profile = self._approval_gate_profile(request)
        positives = int(metrics.get("failurePositiveLabels", 0.0))
        negatives = int(metrics.get("failureNegativeLabels", 0.0))
        label_coverage = float(metrics.get("labelCoverage", 0.0))
        min_positive_labels = profile.minimum_positive_labels
        min_label_coverage = profile.minimum_label_coverage
        if positives < min_positive_labels:
            return (
                "Insufficient positive failure labels: "
                f"required at least {min_positive_labels}, found {positives}."
            )
        if negatives < 1:
            return "Insufficient negative non-failure labels: required at least 1, found 0."
        if label_coverage < min_label_coverage:
            return (
                "Insufficient failure label coverage: "
                f"required at least {min_label_coverage:.4f}, found {label_coverage:.4f}."
            )
        return None

    def approval_metadata(
        self,
        request: TrainingRequest,
        metrics: dict[str, float],
        feature_schema_hash: str,
        evaluation_metadata: dict,
    ) -> dict:
        profile = self._approval_gate_profile(request)
        criteria = [
            self._approval_gate(
                name="minimum_positive_labels",
                observed=self._metric(metrics, "failurePositiveLabels"),
                threshold=float(profile.minimum_positive_labels),
                passed=self._metric_passes_minimum(metrics, "failurePositiveLabels", profile.minimum_positive_labels),
                reason="Requires enough confirmed failure or corrective maintenance labels.",
            ),
            self._approval_gate(
                name="minimum_label_coverage",
                observed=self._metric(metrics, "labelCoverage"),
                threshold=float(profile.minimum_label_coverage),
                passed=self._metric_passes_minimum(metrics, "labelCoverage", profile.minimum_label_coverage),
                reason="Requires enough usable horizon labels for the dataset.",
            ),
            self._approval_gate(
                name="time_based_evaluation_available",
                observed=1.0 if evaluation_metadata.get("status") == "EVALUATED" else 0.0,
                threshold=1.0,
                passed=evaluation_metadata.get("status") == "EVALUATED",
                reason="Requires a time-based holdout with a train split containing both classes.",
            ),
            self._approval_gate(
                name="minimum_precision",
                observed=self._metric(metrics, "failurePrecision"),
                threshold=profile.minimum_precision,
                passed=self._metric_passes_minimum(metrics, "failurePrecision", profile.minimum_precision),
                reason="Requires candidate precision to meet the configured approval threshold.",
            ),
            self._approval_gate(
                name="minimum_recall",
                observed=self._metric(metrics, "failureRecall"),
                threshold=profile.minimum_recall,
                passed=self._metric_passes_minimum(metrics, "failureRecall", profile.minimum_recall),
                reason="Requires candidate recall to meet the configured approval threshold.",
            ),
            self._approval_gate(
                name="minimum_pr_auc_or_lift",
                observed=self._metric(metrics, "failurePrAuc"),
                threshold=profile.minimum_pr_auc,
                passed=(
                    self._metric_passes_minimum(metrics, "failurePrAuc", profile.minimum_pr_auc)
                    or self._metric_passes_minimum(metrics, "failurePrAucLift", profile.minimum_pr_auc_lift)
                ),
                reason="Requires PR-AUC or PR-AUC lift to meet the configured approval profile.",
            ),
            self._approval_gate(
                name="maximum_false_alert_rate",
                observed=self._metric(metrics, "failureFalseAlertsPerDeviceMonth"),
                threshold=profile.maximum_false_alerts_per_device_month,
                passed=self._metric_passes_maximum(
                    metrics,
                    "failureFalseAlertsPerDeviceMonth",
                    profile.maximum_false_alerts_per_device_month,
                ),
                reason="Limits false alerts per device-month before promotion.",
            ),
            self._approval_gate(
                name="minimum_lead_time",
                observed=self._metric(metrics, "failureAverageLeadTimeDays"),
                threshold=profile.minimum_lead_time_days,
                passed=self._metric_passes_minimum(metrics, "failureAverageLeadTimeDays", profile.minimum_lead_time_days),
                reason="Requires enough warning lead time for operational response.",
            ),
            self._approval_gate(
                name="artifact_load_test",
                observed=1.0,
                threshold=1.0,
                passed=profile.require_artifact_load_test,
                reason="Model bundle is saved with a loadable manifest and verified after persistence.",
            ),
            self._approval_gate(
                name="feature_schema_compatibility",
                observed=1.0 if feature_schema_hash else 0.0,
                threshold=1.0,
                passed=bool(feature_schema_hash) if profile.require_feature_schema_compatibility else True,
                reason="Requires a feature schema hash for runtime compatibility checks.",
            ),
        ]
        passed = all(item["passed"] for item in criteria)
        return {
            "approvalGateVersion": "failure-approval-gates-v1",
            "profileId": profile.profile_id,
            "profileVersion": profile.profile_version,
            "thresholdSource": profile.threshold_source,
            "status": "PASSED" if passed else "NEEDS_REVIEW",
            "promotionEligible": passed,
            "criteria": criteria,
        }

    def _approval_gate_profile(self, request: TrainingRequest) -> ApprovalGateProfile:
        profile = request.approval_gate_profile or ApprovalGateProfile()
        values = profile.model_dump()
        if request.minimum_positive_labels is not None:
            values["minimum_positive_labels"] = request.minimum_positive_labels
        if request.minimum_label_coverage is not None:
            values["minimum_label_coverage"] = request.minimum_label_coverage
        return ApprovalGateProfile.model_validate(values)

    def _metric(self, metrics: dict[str, float], key: str) -> float | None:
        value = metrics.get(key)
        return float(value) if isinstance(value, int | float) else None

    def _metric_passes_minimum(self, metrics: dict[str, float], key: str, threshold: float) -> bool:
        value = self._metric(metrics, key)
        return value is not None and value >= threshold

    def _metric_passes_maximum(self, metrics: dict[str, float], key: str, threshold: float) -> bool:
        value = self._metric(metrics, key)
        return value is not None and value <= threshold

    def _approval_gate(
        self,
        name: str,
        observed: float | None,
        threshold: float,
        passed: bool,
        reason: str,
    ) -> dict:
        return {
            "name": name,
            "observed": None if observed is None else round(float(observed), 6),
            "threshold": round(float(threshold), 6),
            "passed": passed,
            "reason": reason,
        }


class ModelCardBuilder:
    def failure_card(
        self,
        model_payload: dict,
        request: TrainingRequest,
        metrics: dict[str, float],
        evaluation_metadata: dict,
        approval_metadata: dict,
    ) -> dict:
        return {
            "modelCardVersion": "failure-prediction-model-card-v1",
            "modelVersionId": model_payload["modelVersionId"],
            "modelName": model_payload["name"],
            "modelVersion": model_payload["version"],
            "modelFamily": "FAILURE_PREDICTION",
            "algorithm": model_payload["algorithm"],
            "intendedUse": {
                "primaryUse": "Estimate device failure or corrective maintenance risk within the configured horizon.",
                "decisionBoundary": "Decision support only; not a component-level diagnosis or automatic shutdown command.",
                "predictionHorizonDays": model_payload["failureHorizonDays"],
            },
            "trainingData": {
                "datasetVersionId": request.dataset_version_id,
                "sourceType": request.source_type,
                "featureSchemaHash": model_payload["featureSchemaHash"],
                "trainingSamples": model_payload["trainingSamples"],
                "positiveLabels": model_payload["positiveLabels"],
                "negativeLabels": model_payload["negativeLabels"],
                "labelCoverage": metrics.get("labelCoverage", 0.0),
            },
            "target": model_payload["targetContract"],
            "evaluation": {
                **evaluation_metadata,
                "metrics": {
                    key: metrics[key]
                    for key in sorted(metrics)
                    if key.startswith("failure")
                    and (
                        key
                        not in {
                            "failureTargetSamples",
                            "failureUsableSamples",
                            "failurePositiveLabels",
                            "failureNegativeLabels",
                            "failureExcludedSamples",
                            "failurePositiveRate",
                            "failureClassImbalanceRatio",
                            "failureHorizonDays",
                        }
                    )
                },
            },
            "approvalGates": approval_metadata,
            "limitations": [
                "Requires outcome-derived labels with enough positive and negative examples.",
                "Risk scores are horizon-specific and should not be interpreted as exact failure dates.",
                "Performance can degrade when live feature schema or device population differs from training data.",
                "Rule-based risk remains separate from supervised failure risk.",
            ],
            "runtime": {
                "artifactContractVersion": model_payload["artifactContractVersion"],
                "scoring": model_payload["scoring"],
                "featureColumns": model_payload["featureColumns"],
            },
        }


class FailurePredictionTrainingRunner:
    def __init__(
        self,
        publisher: ModelArtifactPublisher,
        coordinator: TrainingRunCoordinator,
        gate_evaluator: ApprovalGateEvaluator | None = None,
        model_card_builder: ModelCardBuilder | None = None,
    ) -> None:
        self.publisher = publisher
        self.coordinator = coordinator
        self.gate_evaluator = gate_evaluator or ApprovalGateEvaluator()
        self.model_card_builder = model_card_builder or ModelCardBuilder()

    def run(self, run_id: str, request: TrainingRequest, started_at: float) -> TrainingRunResponse:
        try:
            self.coordinator.checkpoint(request, started_at)
            builder = FailureTargetBuilder()
            samples = builder.build(request)
            metrics = builder.summary(samples)
        except ValueError as exc:
            return self._failed_response(
                run_id,
                request,
                failure_reason=str(exc),
                logs_summary=["Rejected failure-prediction training request before fitting."],
            )

        gate_failure = self.gate_evaluator.failure_reason(request, metrics)
        if gate_failure is not None:
            return self._failed_response(
                run_id,
                request,
                metrics=metrics,
                failure_reason=gate_failure,
                logs_summary=["Rejected failure-prediction training request before fitting."],
            )

        algorithm = self._failure_algorithm(request)
        self.coordinator.checkpoint(request, started_at)
        evaluation = FailurePredictionEvaluator().evaluate(samples, algorithm)
        evaluation_metrics = evaluation["metrics"]
        evaluation_metadata = evaluation["metadata"]

        try:
            model = FailurePredictionModel(algorithm)
            model.fit(samples)
        except ValueError as exc:
            return self._failed_response(
                run_id,
                request,
                metrics=metrics,
                failure_reason=str(exc),
                logs_summary=["Rejected failure-prediction training request before fitting."],
            )

        model_version_id = str(uuid4())
        model_version = f"{algorithm}-{run_id[:8]}"
        model_name = f"failure-{request.dataset_version_id}"
        feature_schema_hash = self.publisher.feature_schema_hash(request)
        metrics = {
            **metrics,
            **evaluation_metrics,
            "failureHorizonDays": float(request.failure_horizon_days or samples[0].horizon_days),
        }
        approval_metadata = self.gate_evaluator.approval_metadata(
            request=request,
            metrics=metrics,
            feature_schema_hash=feature_schema_hash,
            evaluation_metadata=evaluation_metadata,
        )
        metrics = {
            **metrics,
            "approvalGateProfileId": approval_metadata["profileId"],
            "approvalGateProfileVersion": approval_metadata["profileVersion"],
            "approvalGateThresholdSource": approval_metadata["thresholdSource"],
            "approvalGates": approval_metadata,
        }
        model_payload = {
            "name": model_name,
            "version": model_version,
            "modelVersionId": model_version_id,
            **model.model_payload(),
            "algorithm": algorithm,
            "datasetVersionId": request.dataset_version_id,
            "featureSchemaHash": feature_schema_hash,
            "metrics": metrics,
            "evaluation": evaluation_metadata,
            "approvalGates": approval_metadata,
        }
        model_card_payload = self.model_card_builder.failure_card(
            model_payload=model_payload,
            request=request,
            metrics=metrics,
            evaluation_metadata=evaluation_metadata,
            approval_metadata=approval_metadata,
        )
        self.coordinator.checkpoint(request, started_at)
        artifact_bundle_id = self.coordinator.artifact_bundle_id(run_id, request)
        bundle = self.publisher.save_verified_bundle(
            artifact_bundle_id,
            model_payload,
            self.publisher.manifest(
                model_version_id=model_version_id,
                run_id=run_id,
                request=request,
                feature_schema_hash=feature_schema_hash,
                metrics=metrics,
                algorithm=algorithm,
                evaluation_metadata=evaluation_metadata,
                approval_metadata=approval_metadata,
            ),
            supporting_files={
                "model.joblib": model.serialized_pipeline(),
                "model-card.json": model_card_payload,
                "preprocessor.json": model.preprocessor_payload(request.dataset_version_id, feature_schema_hash),
            },
        )
        self.coordinator.checkpoint(request, started_at)
        model_registry_service.register_model(
            {
                "name": model_name,
                "version": model_version,
                "modelVersionId": model_version_id,
                "status": "READY",
                "modelType": model.model_type,
                "artifactUri": bundle["artifactUri"],
                "manifestUri": bundle["manifestUri"],
                "artifactHash": bundle["artifactHash"],
                "artifactSizeBytes": bundle["artifactSizeBytes"],
                "metrics": metrics,
            }
        )
        return TrainingRunResponse(
            runId=run_id,
            status="SUCCEEDED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=artifact_bundle_id,
            modelVersionId=model_version_id,
            modelVersion=model_version,
            artifactUri=bundle["artifactUri"],
            manifestUri=bundle["manifestUri"],
            artifactHash=bundle["artifactHash"],
            artifactSizeBytes=bundle["artifactSizeBytes"],
            metrics=metrics,
            logsSummary=[
                "Constructed horizon targets from generated outcome labels.",
                f"Registered failure-prediction model {model_name}.",
            ],
        )

    def _failed_response(
        self,
        run_id: str,
        request: TrainingRequest,
        *,
        failure_reason: str,
        logs_summary: list[str],
        metrics: dict[str, float] | None = None,
    ) -> TrainingRunResponse:
        return TrainingRunResponse(
            runId=run_id,
            status="FAILED",
            datasetVersionId=request.dataset_version_id,
            trainingRunId=request.training_run_id,
            idempotencyKey=request.idempotency_key,
            artifactBundleId=self.coordinator.artifact_bundle_id(run_id, request),
            metrics=metrics or {},
            failureReason=failure_reason,
            logsSummary=logs_summary,
        )

    def _failure_algorithm(self, request: TrainingRequest) -> str:
        if request.algorithm == "baseline-statistical-anomaly-v1":
            return FailurePredictionModel.LOGISTIC_REGRESSION_ALGORITHM
        return request.algorithm or FailurePredictionModel.LOGISTIC_REGRESSION_ALGORITHM
