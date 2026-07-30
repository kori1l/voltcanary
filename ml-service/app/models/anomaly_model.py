from app.models.baseline_model import BaselineModel
from app.schemas.training import MetricRow


class AnomalyModel:
    def __init__(self) -> None:
        self.baseline = BaselineModel()

    def fit(self, rows: list[MetricRow]) -> None:
        self.baseline.fit(rows)

    def score(self, metrics: dict[str, float]) -> dict[str, float]:
        return self.baseline.predict(metrics)
