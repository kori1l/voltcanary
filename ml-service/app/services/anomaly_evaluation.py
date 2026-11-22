from app.schemas.training import MetricRow
from app.models.tabular_anomaly_model import TabularAnomalyModel


class AnomalyEvaluation:
    """Strict chronological holdout; a timestamp never appears in both partitions."""

    def split(self, rows: list[MetricRow]) -> tuple[list[MetricRow], list[MetricRow]]:
        ordered = sorted(rows, key=lambda row: (row.timestamp, row.device_id, row.metric))
        if any(row.metadata.get("splitAssignment") for row in rows):
            if any(row.metadata.get("splitAssignment") not in {"TRAIN", "VALIDATION", "TEST"} for row in rows):
                raise ValueError("Invalid split assignments")
            training = [row for row in ordered if row.metadata["splitAssignment"] == "TRAIN"]
            validation = [row for row in ordered if row.metadata["splitAssignment"] == "VALIDATION"]
            if not training:
                raise ValueError("Dataset split has no training observations")
            return training, validation
        timestamps = sorted({row.timestamp for row in ordered})
        if len(timestamps) < 2:
            return ordered, []
        cutoff = timestamps[min(len(timestamps) - 1, max(1, int(len(timestamps) * 0.7)))]
        return ([row for row in ordered if row.timestamp < cutoff],
                [row for row in ordered if row.timestamp >= cutoff])

    def evaluate(self, model: TabularAnomalyModel, train: list[MetricRow], validation: list[MetricRow]) -> dict[str, float]:
        metrics = {
            "trainingRows": float(len(train)),
            "validationRows": float(len(validation)),
            "inputRows": float(len(train) + len(validation)),
            "labelCoverage": sum(bool(row.label) for row in train) / len(train) if train else 0.0,
        }
        if validation:
            scores = [model.score({row.metric: row.value})["anomalyScore"] for row in validation]
            metrics["validationAnomalyScore"] = sum(scores) / len(scores)
        return metrics
