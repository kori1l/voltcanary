from statistics import mean, pstdev

from app.schemas.training import MetricRow


class BaselineModel:
    def __init__(self) -> None:
        self.metric_stats: dict[str, dict[str, float]] = {}

    def fit(self, rows: list[MetricRow]) -> None:
        values_by_metric: dict[str, list[float]] = {}
        for row in rows:
            values_by_metric.setdefault(row.metric, []).append(float(row.value))

        self.metric_stats = {}
        for metric, values in sorted(values_by_metric.items()):
            metric_mean = mean(values)
            metric_stddev = pstdev(values) if len(values) > 1 else 0.0
            self.metric_stats[metric] = {
                "mean": metric_mean,
                "stddev": metric_stddev,
                "min": min(values),
                "max": max(values),
                "count": float(len(values)),
            }

    def predict(self, metrics: dict[str, float]) -> dict[str, float]:
        if not self.metric_stats:
            return {"anomalyScore": 0.0, "confidence": 0.15}

        scores: list[float] = []
        for metric, value in metrics.items():
            stats = self.metric_stats.get(metric)
            if stats is None:
                continue
            spread = stats["stddev"] or max((stats["max"] - stats["min"]) / 4.0, 1.0)
            z_score = abs(float(value) - stats["mean"]) / spread
            scores.append(min(1.0, z_score / 4.0))

        if not scores:
            return {"anomalyScore": 0.0, "confidence": 0.25}
        return {"anomalyScore": max(scores), "confidence": min(0.95, 0.65 + 0.05 * len(scores))}
