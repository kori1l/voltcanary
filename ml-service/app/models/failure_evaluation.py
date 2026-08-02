from dataclasses import dataclass
from typing import Any

from app.models.failure_prediction_model import FailurePredictionModel
from app.models.failure_target import FailureTargetSample


@dataclass(frozen=True)
class FailureEvaluationSplit:
    train_samples: list[FailureTargetSample]
    validation_samples: list[FailureTargetSample]


class FailurePredictionEvaluator:
    def evaluate(self, samples: list[FailureTargetSample], algorithm: str) -> dict[str, Any]:
        split = self.time_based_split(samples)
        if split is None:
            return {
                "metadata": {
                    "strategy": "time_based_holdout",
                    "status": "INSUFFICIENT_TIME_SPLIT",
                },
                "metrics": {
                    "failureTrainingSplitSamples": 0.0,
                    "failureValidationSamples": 0.0,
                },
            }

        model = FailurePredictionModel(algorithm)
        model.fit(split.train_samples)
        scores = [model.predict(sample.features)["failureRiskScore"] for sample in split.validation_samples]
        labels = [int(sample.target or 0) for sample in split.validation_samples]
        predictions = [1 if score >= 0.5 else 0 for score in scores]
        precision, recall, f1 = self._classification_metrics(labels, predictions)
        baseline_score = self._training_positive_rate(split.train_samples)
        baseline_scores = [baseline_score for _ in split.validation_samples]
        baseline_predictions = [1 if score >= 0.5 else 0 for score in baseline_scores]
        baseline_precision, baseline_recall, baseline_f1 = self._classification_metrics(labels, baseline_predictions)
        candidate_pr_auc = self._pr_auc(labels, scores)
        baseline_pr_auc = self._pr_auc(labels, baseline_scores)
        return {
            "metadata": {
                "strategy": "time_based_holdout",
                "status": "EVALUATED",
                "threshold": 0.5,
                "baseline": {
                    "name": "training-positive-rate-baseline-v1",
                    "description": "No-skill failure baseline using the training split positive label rate.",
                    "score": baseline_score,
                },
            },
            "metrics": {
                "failureTrainingSplitSamples": float(len(split.train_samples)),
                "failureValidationSamples": float(len(split.validation_samples)),
                "failureValidationPositiveLabels": float(sum(labels)),
                "failurePrecision": precision,
                "failureRecall": recall,
                "failureF1": f1,
                "failurePrAuc": candidate_pr_auc,
                "failureFalseAlertsPerDeviceMonth": self._false_alerts_per_device_month(split.validation_samples, labels, predictions),
                "failureAverageLeadTimeDays": self._average_lead_time_days(split.validation_samples, labels, predictions),
                "failureBaselinePositiveRate": baseline_score,
                "failureBaselinePrecision": baseline_precision,
                "failureBaselineRecall": baseline_recall,
                "failureBaselineF1": baseline_f1,
                "failureBaselinePrAuc": baseline_pr_auc,
                "failureBaselineFalseAlertsPerDeviceMonth": self._false_alerts_per_device_month(
                    split.validation_samples,
                    labels,
                    baseline_predictions,
                ),
                "failurePrAucLift": round(candidate_pr_auc - baseline_pr_auc, 6),
                "failureF1Lift": round(f1 - baseline_f1, 6),
            },
        }

    def time_based_split(self, samples: list[FailureTargetSample]) -> FailureEvaluationSplit | None:
        usable = sorted(
            [sample for sample in samples if sample.target is not None and sample.features],
            key=lambda sample: (sample.prediction_at, sample.device_id, sample.sample_id),
        )
        if len(usable) < 4:
            return None

        preferred_validation_count = max(1, round(len(usable) * 0.3))
        for validation_count in range(preferred_validation_count, 0, -1):
            split_index = len(usable) - validation_count
            train_samples = usable[:split_index]
            validation_samples = usable[split_index:]
            if self._has_both_classes(train_samples) and validation_samples:
                return FailureEvaluationSplit(train_samples=train_samples, validation_samples=validation_samples)
        return None

    def _classification_metrics(self, labels: list[int], predictions: list[int]) -> tuple[float, float, float]:
        true_positives = sum(1 for label, prediction in zip(labels, predictions, strict=False) if label == 1 and prediction == 1)
        false_positives = sum(1 for label, prediction in zip(labels, predictions, strict=False) if label == 0 and prediction == 1)
        false_negatives = sum(1 for label, prediction in zip(labels, predictions, strict=False) if label == 1 and prediction == 0)
        precision = true_positives / (true_positives + false_positives) if true_positives + false_positives else 0.0
        recall = true_positives / (true_positives + false_negatives) if true_positives + false_negatives else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        return round(precision, 6), round(recall, 6), round(f1, 6)

    def _pr_auc(self, labels: list[int], scores: list[float]) -> float:
        if len(set(labels)) < 2:
            return round(sum(labels) / len(labels), 6) if labels else 0.0
        from sklearn.metrics import average_precision_score

        return round(float(average_precision_score(labels, scores)), 6)

    def _false_alerts_per_device_month(
        self,
        samples: list[FailureTargetSample],
        labels: list[int],
        predictions: list[int],
    ) -> float:
        false_positives = sum(1 for label, prediction in zip(labels, predictions, strict=False) if label == 0 and prediction == 1)
        device_count = max(1, len({sample.device_id for sample in samples}))
        timestamps = [sample.prediction_at for sample in samples]
        observed_days = max(1.0, (max(timestamps) - min(timestamps)).total_seconds() / 86400) if timestamps else 1.0
        device_months = device_count * (observed_days / 30)
        return round(false_positives / device_months, 6) if device_months else 0.0

    def _average_lead_time_days(
        self,
        samples: list[FailureTargetSample],
        labels: list[int],
        predictions: list[int],
    ) -> float:
        lead_times = [
            sample.lead_time_days
            for sample, label, prediction in zip(samples, labels, predictions, strict=False)
            if label == 1 and prediction == 1 and sample.lead_time_days is not None
        ]
        return round(sum(lead_times) / len(lead_times), 6) if lead_times else 0.0

    def _has_both_classes(self, samples: list[FailureTargetSample]) -> bool:
        targets = {sample.target for sample in samples}
        return 0 in targets and 1 in targets

    def _training_positive_rate(self, samples: list[FailureTargetSample]) -> float:
        if not samples:
            return 0.0
        positives = sum(1 for sample in samples if sample.target == 1)
        return round(positives / len(samples), 6)
