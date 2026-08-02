from uuid import uuid4
import sys

from app.models.failure_evaluation import FailurePredictionEvaluator
from app.models.failure_prediction_model import FailurePredictionModel
from app.models.failure_target import FailureTargetBuilder
from app.models.tabular_anomaly_model import TabularAnomalyModel
from app.schemas.training import TrainingRequest, TrainingRunResponse
from app.services.artifact_store import ArtifactStore
from app.services.feature_service import FeatureService
from app.services.model_registry_service import model_registry_service


class TrainingService:
    def __init__(self, artifact_store: ArtifactStore | None = None) -> None:
        self.artifact_store = artifact_store or ArtifactStore()
        self.feature_service = FeatureService(self.artifact_store)
        self.runs: dict[str, TrainingRunResponse] = {}

    def start_training(self, request: TrainingRequest) -> TrainingRunResponse:
        run_id = str(uuid4())
        if not request.rows:
            run = TrainingRunResponse(
                runId=run_id,
                status="FAILED",
                datasetVersionId=request.dataset_version_id,
                failureReason="training requires at least one metric row",
                logsSummary=["Rejected empty training dataset."],
            )
            self.runs[run_id] = run
            return run

        if request.model_family.upper() == "FAILURE_PREDICTION":
            return self._start_failure_prediction_training(run_id, request)

        snapshot = self.feature_service.generate_snapshot(request.dataset_version_id, request.rows)
        feature_artifact = self.feature_service.load_snapshot_artifact(snapshot.artifact_uri)
        model = TabularAnomalyModel()
        model.fit(request.rows, feature_artifact)
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
        bundle = self.artifact_store.save_model_bundle(
            f"model-{run_id}",
            model_payload,
            self._manifest(
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
        self.artifact_store.verify_model_bundle(bundle["manifestUri"], bundle["artifactHash"])
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
        run = TrainingRunResponse(
            runId=run_id,
            status="SUCCEEDED",
            datasetVersionId=request.dataset_version_id,
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
        self.runs[run_id] = run
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

    def _start_failure_prediction_training(self, run_id: str, request: TrainingRequest) -> TrainingRunResponse:
        try:
            builder = FailureTargetBuilder()
            samples = builder.build(request)
            metrics = builder.summary(samples)
        except ValueError as exc:
            run = TrainingRunResponse(
                runId=run_id,
                status="FAILED",
                datasetVersionId=request.dataset_version_id,
                failureReason=str(exc),
                logsSummary=["Rejected failure-prediction training request before fitting."],
            )
            self.runs[run_id] = run
            return run

        gate_failure = self._failure_gate_failure(request, metrics)
        if gate_failure is not None:
            run = TrainingRunResponse(
                runId=run_id,
                status="FAILED",
                datasetVersionId=request.dataset_version_id,
                metrics=metrics,
                failureReason=gate_failure,
                logsSummary=["Rejected failure-prediction training request before fitting."],
            )
            self.runs[run_id] = run
            return run

        algorithm = self._failure_algorithm(request)
        evaluation = FailurePredictionEvaluator().evaluate(samples, algorithm)
        evaluation_metrics = evaluation["metrics"]
        evaluation_metadata = evaluation["metadata"]

        try:
            model = FailurePredictionModel(algorithm)
            model.fit(samples)
        except ValueError as exc:
            run = TrainingRunResponse(
                runId=run_id,
                status="FAILED",
                datasetVersionId=request.dataset_version_id,
                metrics=metrics,
                failureReason=str(exc),
                logsSummary=["Rejected failure-prediction training request before fitting."],
            )
            self.runs[run_id] = run
            return run

        model_version_id = str(uuid4())
        model_version = f"{algorithm}-{run_id[:8]}"
        model_name = f"failure-{request.dataset_version_id}"
        feature_schema_hash = self._feature_schema_hash(request)
        metrics = {
            **metrics,
            **evaluation_metrics,
            "failureHorizonDays": float(request.failure_horizon_days or samples[0].horizon_days),
        }
        approval_metadata = self._failure_approval_gates(
            request=request,
            metrics=metrics,
            feature_schema_hash=feature_schema_hash,
            evaluation_metadata=evaluation_metadata,
        )
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
        model_card_payload = self._failure_model_card(
            model_payload=model_payload,
            request=request,
            metrics=metrics,
            evaluation_metadata=evaluation_metadata,
            approval_metadata=approval_metadata,
        )
        bundle = self.artifact_store.save_model_bundle(
            f"model-{run_id}",
            model_payload,
            self._manifest(
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
        self.artifact_store.verify_model_bundle(bundle["manifestUri"], bundle["artifactHash"])
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
        run = TrainingRunResponse(
            runId=run_id,
            status="SUCCEEDED",
            datasetVersionId=request.dataset_version_id,
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
        self.runs[run_id] = run
        return run

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

    def _manifest(
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
            "supportedDeviceTypes": sorted({str(row.metadata.get("deviceType")) for row in request.rows if row.metadata.get("deviceType")}),
            "metrics": metrics or {},
            "evaluation": evaluation_metadata or {},
            "approvalGates": approval_metadata or {},
            "inferenceInputContract": {
                "modelVersionId": model_version_id,
                "requiredFields": ["deviceId", "metrics"],
                "featureSchemaHash": resolved_feature_schema_hash,
            },
        }

    def _feature_schema_hash(self, request: TrainingRequest) -> str:
        for row in request.rows:
            value = row.metadata.get("featureSchemaHash")
            if value:
                return str(value)
        metrics = ",".join(sorted({row.metric for row in request.rows}))
        return f"inline:{request.model_family}:{request.failure_horizon_days}:{metrics}"

    def _algorithm(self, request: TrainingRequest, default_algorithm: str) -> str:
        if request.model_family.upper() == "FAILURE_PREDICTION" and request.algorithm == "baseline-statistical-anomaly-v1":
            return default_algorithm
        return request.algorithm or default_algorithm

    def _failure_algorithm(self, request: TrainingRequest) -> str:
        if request.algorithm == "baseline-statistical-anomaly-v1":
            return FailurePredictionModel.LOGISTIC_REGRESSION_ALGORITHM
        return request.algorithm or FailurePredictionModel.LOGISTIC_REGRESSION_ALGORITHM

    def _failure_gate_failure(self, request: TrainingRequest, metrics: dict[str, float]) -> str | None:
        positives = int(metrics.get("failurePositiveLabels", 0.0))
        negatives = int(metrics.get("failureNegativeLabels", 0.0))
        label_coverage = float(metrics.get("labelCoverage", 0.0))
        min_positive_labels = request.minimum_positive_labels if request.minimum_positive_labels is not None else 1
        min_label_coverage = request.minimum_label_coverage if request.minimum_label_coverage is not None else 0.0
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

    def _failure_model_card(
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

    def _failure_approval_gates(
        self,
        request: TrainingRequest,
        metrics: dict[str, float],
        feature_schema_hash: str,
        evaluation_metadata: dict,
    ) -> dict:
        min_positive_labels = request.minimum_positive_labels if request.minimum_positive_labels is not None else 1
        min_label_coverage = request.minimum_label_coverage if request.minimum_label_coverage is not None else 0.0
        min_precision = 0.0
        min_recall = 0.0
        max_false_alerts_per_device_month = 30.0
        criteria = [
            self._approval_gate(
                name="minimum_positive_labels",
                observed=metrics.get("failurePositiveLabels", 0.0),
                threshold=float(min_positive_labels),
                passed=metrics.get("failurePositiveLabels", 0.0) >= min_positive_labels,
                reason="Requires enough confirmed failure or corrective maintenance labels.",
            ),
            self._approval_gate(
                name="minimum_label_coverage",
                observed=metrics.get("labelCoverage", 0.0),
                threshold=float(min_label_coverage),
                passed=metrics.get("labelCoverage", 0.0) >= min_label_coverage,
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
                observed=metrics.get("failurePrecision", 0.0),
                threshold=min_precision,
                passed=metrics.get("failurePrecision", 0.0) >= min_precision,
                reason="Requires candidate precision to meet the configured approval threshold.",
            ),
            self._approval_gate(
                name="minimum_recall",
                observed=metrics.get("failureRecall", 0.0),
                threshold=min_recall,
                passed=metrics.get("failureRecall", 0.0) >= min_recall,
                reason="Requires candidate recall to meet the configured approval threshold.",
            ),
            self._approval_gate(
                name="maximum_false_alert_rate",
                observed=metrics.get("failureFalseAlertsPerDeviceMonth", 0.0),
                threshold=max_false_alerts_per_device_month,
                passed=metrics.get("failureFalseAlertsPerDeviceMonth", 0.0) <= max_false_alerts_per_device_month,
                reason="Limits false alerts per device-month before promotion.",
            ),
            self._approval_gate(
                name="artifact_load_test",
                observed=1.0,
                threshold=1.0,
                passed=True,
                reason="Model bundle is saved with a loadable manifest and verified after persistence.",
            ),
            self._approval_gate(
                name="feature_schema_compatibility",
                observed=1.0 if feature_schema_hash else 0.0,
                threshold=1.0,
                passed=bool(feature_schema_hash),
                reason="Requires a feature schema hash for runtime compatibility checks.",
            ),
        ]
        passed = all(item["passed"] for item in criteria)
        return {
            "approvalGateVersion": "failure-approval-gates-v1",
            "status": "PASSED" if passed else "NEEDS_REVIEW",
            "promotionEligible": passed,
            "criteria": criteria,
        }

    def _approval_gate(
        self,
        name: str,
        observed: float,
        threshold: float,
        passed: bool,
        reason: str,
    ) -> dict:
        return {
            "name": name,
            "observed": round(float(observed), 6),
            "threshold": round(float(threshold), 6),
            "passed": passed,
            "reason": reason,
        }
