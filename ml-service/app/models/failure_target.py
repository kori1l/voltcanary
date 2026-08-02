from dataclasses import dataclass
from datetime import datetime
from typing import Any

from app.schemas.training import MetricRow, TrainingRequest


POSITIVE_OUTCOMES = {"CONFIRMED_FAILURE", "CORRECTIVE_MAINTENANCE"}
NEGATIVE_OUTCOMES = {"NORMAL", "INSPECTION_NORMAL", "FALSE_POSITIVE"}
EXCLUDED_OUTCOMES = {
    "MAINTENANCE",
    "PREVENTIVE_MAINTENANCE",
    "SENSOR_FAULT",
    "UNRESOLVED_ANOMALY",
    "UNKNOWN",
}


@dataclass(frozen=True)
class FailureTargetSample:
    sample_id: str
    device_id: str
    prediction_at: datetime
    horizon_days: int
    features: dict[str, float]
    target: int | None
    outcome_type: str | None
    exclusion_reason: str | None
    lead_time_days: float | None


class FailureTargetBuilder:
    def build(self, request: TrainingRequest) -> list[FailureTargetSample]:
        horizon_days = request.failure_horizon_days
        if horizon_days is None:
            horizon_days = self._infer_horizon_days(request.rows)
        if horizon_days is None or horizon_days < 1:
            raise ValueError("FAILURE_PREDICTION target construction requires failureHorizonDays")

        grouped: dict[str, list[MetricRow]] = {}
        for row in request.rows:
            grouped.setdefault(self._sample_id(row), []).append(row)

        samples = [self._sample(rows, horizon_days) for rows in grouped.values()]
        return sorted(samples, key=lambda sample: (sample.prediction_at, sample.device_id, sample.sample_id))

    def usable_samples(self, samples: list[FailureTargetSample]) -> list[FailureTargetSample]:
        return [sample for sample in samples if sample.target is not None and sample.features]

    def summary(self, samples: list[FailureTargetSample]) -> dict[str, float]:
        usable = self.usable_samples(samples)
        positives = sum(1 for sample in usable if sample.target == 1)
        negatives = sum(1 for sample in usable if sample.target == 0)
        excluded = len(samples) - len(usable)
        minority = min(positives, negatives)
        majority = max(positives, negatives)
        return {
            "failureTargetSamples": float(len(samples)),
            "failureUsableSamples": float(len(usable)),
            "failurePositiveLabels": float(positives),
            "failureNegativeLabels": float(negatives),
            "failureExcludedSamples": float(excluded),
            "failurePositiveRate": round(positives / len(usable), 6) if usable else 0.0,
            "failureClassImbalanceRatio": round(majority / minority, 6) if minority else 0.0,
            "labelCoverage": round(len(usable) / len(samples), 6) if samples else 0.0,
        }

    def _sample(self, rows: list[MetricRow], horizon_days: int) -> FailureTargetSample:
        first = min(rows, key=lambda row: (row.timestamp, row.metric))
        features = {row.metric: float(row.value) for row in rows}
        label_values = self._label_values(rows)
        outcome_type = self._outcome_type(label_values, first.label)
        target = self._target(label_values, outcome_type)
        exclusion_reason = None
        if target is None:
            exclusion_reason = self._exclusion_reason(outcome_type)
        return FailureTargetSample(
            sample_id=self._sample_id(first),
            device_id=first.device_id,
            prediction_at=first.timestamp,
            horizon_days=horizon_days,
            features=features,
            target=target,
            outcome_type=outcome_type,
            exclusion_reason=exclusion_reason,
            lead_time_days=self._lead_time_days(label_values, first.timestamp, target),
        )

    def _sample_id(self, row: MetricRow) -> str:
        value = row.metadata.get("featureRowId") or row.metadata.get("entityId")
        if value:
            return str(value)
        return f"{row.device_id}|{row.timestamp.isoformat()}"

    def _label_values(self, rows: list[MetricRow]) -> dict[str, Any]:
        for row in rows:
            if "failureWithinHorizon" in row.metadata or "failureOutcomeType" in row.metadata:
                return row.metadata
        for row in rows:
            if row.label:
                return {"label": row.label}
        return {}

    def _infer_horizon_days(self, rows: list[MetricRow]) -> int | None:
        for row in rows:
            value = row.metadata.get("horizonDays")
            if isinstance(value, int):
                return value
            if isinstance(value, float) and value.is_integer():
                return int(value)
        return None

    def _outcome_type(self, label_values: dict[str, Any], label: str | None) -> str | None:
        value = label_values.get("failureOutcomeType") or label_values.get("label") or label
        return str(value).upper() if value else None

    def _target(self, label_values: dict[str, Any], outcome_type: str | None) -> int | None:
        if isinstance(label_values.get("failureWithinHorizon"), bool):
            if label_values.get("failureWithinHorizon"):
                return 1
            if outcome_type in EXCLUDED_OUTCOMES or outcome_type is None:
                return None
            return 0
        if outcome_type in POSITIVE_OUTCOMES:
            return 1
        if outcome_type in NEGATIVE_OUTCOMES:
            return 0
        return None

    def _exclusion_reason(self, outcome_type: str | None) -> str:
        if outcome_type is None:
            return "UNLABELED_OR_UNOBSERVABLE"
        if outcome_type in EXCLUDED_OUTCOMES:
            return outcome_type
        return "UNSUPPORTED_LABEL"

    def _lead_time_days(self, label_values: dict[str, Any], prediction_at: datetime, target: int | None) -> float | None:
        if target != 1:
            return None
        explicit_lead_time = label_values.get("leadTimeDays")
        if isinstance(explicit_lead_time, int | float):
            return round(float(explicit_lead_time), 6)
        outcome_at_value = label_values.get("failureOutcomeAt") or label_values.get("outcomeAt")
        if not outcome_at_value:
            return None
        try:
            outcome_at = datetime.fromisoformat(str(outcome_at_value).replace("Z", "+00:00"))
        except ValueError:
            return None
        return round(max(0.0, (outcome_at - prediction_at).total_seconds() / 86400), 6)
