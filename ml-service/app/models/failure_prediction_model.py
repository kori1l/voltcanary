from io import BytesIO
from typing import Any

import joblib

from app.models.failure_target import EXCLUDED_OUTCOMES, NEGATIVE_OUTCOMES, POSITIVE_OUTCOMES, FailureTargetSample


class FailurePredictionModel:
    LOGISTIC_REGRESSION_ALGORITHM = "sklearn-logistic-regression-failure-v1"
    RANDOM_FOREST_ALGORITHM = "sklearn-random-forest-failure-v1"
    SUPPORTED_ALGORITHMS = {LOGISTIC_REGRESSION_ALGORITHM, RANDOM_FOREST_ALGORITHM}
    algorithm = LOGISTIC_REGRESSION_ALGORITHM
    model_type = "trained_failure_prediction"
    artifact_contract_version = "failure-prediction-artifact-v1"

    def __init__(self, algorithm: str | None = None) -> None:
        resolved_algorithm = algorithm or self.LOGISTIC_REGRESSION_ALGORITHM
        if resolved_algorithm not in self.SUPPORTED_ALGORITHMS:
            supported = ", ".join(sorted(self.SUPPORTED_ALGORITHMS))
            raise ValueError(f"Unsupported FAILURE_PREDICTION algorithm: {resolved_algorithm}. Supported: {supported}")
        self.algorithm = resolved_algorithm
        self.pipeline: Any | None = None
        self.feature_columns: list[str] = []
        self.horizon_days: int | None = None
        self.training_samples = 0
        self.positive_labels = 0
        self.negative_labels = 0

    def fit(self, samples: list[FailureTargetSample]) -> None:
        usable = [sample for sample in samples if sample.target is not None and sample.features]
        positives = sum(1 for sample in usable if sample.target == 1)
        negatives = sum(1 for sample in usable if sample.target == 0)
        if positives < 1:
            raise ValueError("FAILURE_PREDICTION training requires at least one positive failure label")
        if negatives < 1:
            raise ValueError("FAILURE_PREDICTION training requires at least one negative non-failure label")

        from sklearn.feature_extraction import DictVectorizer
        from sklearn.pipeline import Pipeline

        x = [sample.features for sample in usable]
        y = [int(sample.target) for sample in usable]
        self.pipeline = Pipeline([
            ("vectorizer", DictVectorizer(sparse=False, sort=True)),
            *self._estimator_steps(),
        ])
        self.pipeline.fit(x, y)
        vectorizer = self.pipeline.named_steps["vectorizer"]
        self.feature_columns = list(vectorizer.feature_names_)
        self.horizon_days = usable[0].horizon_days
        self.training_samples = len(usable)
        self.positive_labels = positives
        self.negative_labels = negatives

    def predict(self, features: dict[str, float]) -> dict[str, Any]:
        if self.pipeline is None:
            raise ValueError("failure prediction model has not been fitted")
        probability = float(self.pipeline.predict_proba([features])[0][1])
        risk_drivers = self.risk_drivers(features)
        return {
            "failureRiskScore": max(0.0, min(1.0, probability)),
            "failureRiskBand": self.risk_band(probability),
            "riskDrivers": risk_drivers,
        }

    def risk_drivers(self, features: dict[str, float], limit: int = 5) -> list[dict[str, Any]]:
        if self.pipeline is None:
            return []
        weights = self._feature_weights()
        drivers = []
        for name, value in features.items():
            weight = float(weights.get(name, 0.0))
            contribution = weight * float(value)
            drivers.append(
                {
                    "feature": name,
                    "value": float(value),
                    "weight": round(weight, 6),
                    "contribution": round(contribution, 6),
                    "direction": self._driver_direction(contribution),
                }
            )
        return sorted(drivers, key=lambda item: abs(float(item["contribution"])), reverse=True)[:limit]

    def serialized_pipeline(self) -> bytes:
        if self.pipeline is None:
            raise ValueError("failure prediction model has not been fitted")
        buffer = BytesIO()
        joblib.dump(self.pipeline, buffer)
        return buffer.getvalue()

    @staticmethod
    def load_pipeline(payload: bytes) -> Any:
        return joblib.load(BytesIO(payload))

    @staticmethod
    def score_with_pipeline(pipeline: Any, features: dict[str, float]) -> dict[str, Any]:
        estimator = pipeline.named_steps["model"]
        algorithm = (
            FailurePredictionModel.RANDOM_FOREST_ALGORITHM
            if hasattr(estimator, "feature_importances_")
            else FailurePredictionModel.LOGISTIC_REGRESSION_ALGORITHM
        )
        model = FailurePredictionModel(algorithm)
        model.pipeline = pipeline
        vectorizer = pipeline.named_steps["vectorizer"]
        model.feature_columns = list(vectorizer.feature_names_)
        return model.predict(features)

    def model_payload(self) -> dict[str, Any]:
        return {
            "artifactContractVersion": self.artifact_contract_version,
            "algorithm": self.algorithm,
            "modelType": self.model_type,
            "modelFamily": "FAILURE_PREDICTION",
            "scoring": "sklearn-classifier-probability-v1",
            "estimatorType": self._estimator_type(),
            "featureColumns": self.feature_columns,
            "failureHorizonDays": self.horizon_days,
            "trainingSamples": self.training_samples,
            "positiveLabels": self.positive_labels,
            "negativeLabels": self.negative_labels,
            "targetContract": self.target_contract_payload(),
        }

    def preprocessor_payload(self, dataset_version_id: str, feature_schema_hash: str) -> dict[str, Any]:
        return {
            "artifactContractVersion": self.artifact_contract_version,
            "preprocessorType": "dict-vectorizer-standard-scaler-v1",
            "runtimeLoader": "sklearn-joblib-dict-vectorizer-v1",
            "modelFamily": "FAILURE_PREDICTION",
            "modelType": self.model_type,
            "algorithm": self.algorithm,
            "datasetVersionId": dataset_version_id,
            "featureSchemaHash": feature_schema_hash,
            "featureColumns": self.feature_columns,
            "failureHorizonDays": self.horizon_days,
            "trainingSamples": self.training_samples,
            "positiveLabels": self.positive_labels,
            "negativeLabels": self.negative_labels,
            "targetContract": self.target_contract_payload(),
        }

    def target_contract_payload(self) -> dict[str, Any]:
        return {
            "targetColumn": "failureWithinHorizon",
            "outcomeColumn": "failureOutcomeType",
            "horizonColumn": "horizonDays",
            "positiveOutcomes": sorted(POSITIVE_OUTCOMES),
            "negativeOutcomes": sorted(NEGATIVE_OUTCOMES),
            "excludedOutcomes": sorted(EXCLUDED_OUTCOMES),
        }

    @staticmethod
    def risk_band(score: float) -> str:
        if score >= 0.8:
            return "CRITICAL"
        if score >= 0.6:
            return "HIGH"
        if score >= 0.35:
            return "MEDIUM"
        return "LOW"

    def _estimator_steps(self) -> list[tuple[str, Any]]:
        if self.algorithm == self.LOGISTIC_REGRESSION_ALGORITHM:
            from sklearn.linear_model import LogisticRegression
            from sklearn.preprocessing import StandardScaler

            return [
                ("scaler", StandardScaler()),
                (
                    "model",
                    LogisticRegression(
                        class_weight="balanced",
                        max_iter=1000,
                        random_state=42,
                        solver="liblinear",
                    ),
                ),
            ]
        from sklearn.ensemble import RandomForestClassifier

        return [
            (
                "model",
                RandomForestClassifier(
                    class_weight="balanced_subsample",
                    max_depth=8,
                    min_samples_leaf=1,
                    n_estimators=200,
                    n_jobs=1,
                    random_state=42,
                ),
            )
        ]

    def _feature_weights(self) -> dict[str, float]:
        if self.pipeline is None:
            return {}
        estimator = self.pipeline.named_steps["model"]
        if hasattr(estimator, "coef_"):
            values = list(estimator.coef_[0])
            return dict(zip(self.feature_columns, values, strict=False))
        if hasattr(estimator, "feature_importances_"):
            values = list(estimator.feature_importances_)
            return dict(zip(self.feature_columns, values, strict=False))
        return {}

    def _driver_direction(self, contribution: float) -> str:
        if self.algorithm == self.LOGISTIC_REGRESSION_ALGORITHM:
            return "increases_risk" if contribution >= 0 else "decreases_risk"
        return "model_importance"

    def _estimator_type(self) -> str:
        if self.algorithm == self.LOGISTIC_REGRESSION_ALGORITHM:
            return "logistic_regression"
        return "random_forest"
