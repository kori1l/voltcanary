from io import BytesIO
from statistics import mean, median, pstdev
from typing import Any

import joblib

from app.schemas.training import MetricRow


class TabularAnomalyModel:
    algorithm = "sklearn-isolation-forest-v1"

    def __init__(self) -> None:
        self.metric_stats: dict[str, dict[str, float]] = {}
        self.feature_columns: list[str] = []
        self.training_rows = 0
        self.label_coverage = 0.0
        self.pipeline: Any | None = None

    def fit(self, rows: list[MetricRow], feature_artifact: dict[str, Any]) -> None:
        from sklearn.ensemble import IsolationForest
        from sklearn.feature_extraction import DictVectorizer
        from sklearn.pipeline import Pipeline

        values_by_metric: dict[str, list[float]] = {}
        labels = 0
        for row in rows:
            values_by_metric.setdefault(row.metric, []).append(float(row.value))
            if row.label:
                labels += 1

        self.training_rows = len(rows)
        self.label_coverage = labels / len(rows) if rows else 0.0
        self.feature_columns = sorted((feature_artifact.get("features") or {}).keys())
        self.metric_stats = {
            metric: self._metric_stats(values)
            for metric, values in sorted(values_by_metric.items())
        }
        contamination = min(0.25, max(0.01, self.label_coverage or 0.05))
        self.pipeline = Pipeline(
            [
                ("vectorizer", DictVectorizer(sparse=False)),
                ("model", IsolationForest(contamination=contamination, random_state=42, n_estimators=100)),
            ]
        )
        self.pipeline.fit(self._samples({row.metric: float(row.value) for row in rows}))

    def score(self, metrics: dict[str, float]) -> dict[str, Any]:
        if self.pipeline is not None:
            return self.score_with_pipeline(self.pipeline, metrics)
        metric_scores: dict[str, float] = {}
        for metric, value in metrics.items():
            stats = self.metric_stats.get(metric)
            if stats is None:
                continue
            scale = stats["mad"] or stats["stddev"] or max((stats["max"] - stats["min"]) / 4.0, 1.0)
            robust_z = abs(value - stats["median"]) / scale
            metric_scores[metric] = min(1.0, robust_z / 6.0)
        anomaly_score = max(metric_scores.values(), default=0.0)
        return {
            "anomalyScore": anomaly_score,
            "metricScores": metric_scores,
        }

    def serialized_pipeline(self) -> bytes:
        if self.pipeline is None:
            raise ValueError("model pipeline has not been fitted")
        buffer = BytesIO()
        joblib.dump(self.pipeline, buffer)
        return buffer.getvalue()

    @staticmethod
    def load_pipeline(payload: bytes) -> Any:
        return joblib.load(BytesIO(payload))

    @staticmethod
    def score_with_pipeline(pipeline: Any, metrics: dict[str, float]) -> dict[str, Any]:
        if not metrics:
            return {"anomalyScore": 0.0, "metricScores": {}}
        samples = TabularAnomalyModel._samples(metrics)
        raw_scores = -pipeline.decision_function(samples)
        metric_scores = {
            metric: min(1.0, max(0.0, (float(raw_score) + 0.2) / 0.8))
            for metric, raw_score in zip(metrics, raw_scores, strict=False)
        }
        return {
            "anomalyScore": max(metric_scores.values(), default=0.0),
            "metricScores": metric_scores,
        }

    def preprocessor_payload(self, dataset_version_id: str, feature_artifact: dict[str, Any]) -> dict[str, Any]:
        return {
            "preprocessorType": "tabular-metric-robust-scaler",
            "datasetVersionId": dataset_version_id,
            "featureSchemaHash": feature_artifact["featureSchemaHash"],
            "featureColumns": self.feature_columns,
            "metricColumns": sorted(self.metric_stats),
            "scaling": {
                metric: {
                    "center": stats["median"],
                    "scale": stats["mad"] or stats["stddev"] or 1.0,
                }
                for metric, stats in self.metric_stats.items()
            },
        }

    def model_payload(self) -> dict[str, Any]:
        return {
            "algorithm": self.algorithm,
            "modelType": "trained_tabular_anomaly",
            "scoring": "sklearn-isolation-forest-decision-function-v1",
            "metricStats": self.metric_stats,
            "trainingRows": self.training_rows,
            "labelCoverage": round(self.label_coverage, 6),
        }

    @staticmethod
    def _samples(metrics: dict[str, float]) -> list[dict[str, float]]:
        return [
            {
                f"metric={metric}": 1.0,
                "value": float(value),
            }
            for metric, value in metrics.items()
        ]

    def _metric_stats(self, values: list[float]) -> dict[str, float]:
        center = median(values)
        absolute_deviations = [abs(value - center) for value in values]
        mad = median(absolute_deviations) if absolute_deviations else 0.0
        return {
            "count": float(len(values)),
            "mean": round(mean(values), 6),
            "median": round(center, 6),
            "mad": round(mad, 6),
            "min": round(min(values), 6),
            "max": round(max(values), 6),
            "stddev": round(pstdev(values), 6) if len(values) > 1 else 0.0,
        }
