import unittest
from datetime import datetime, timedelta, timezone

from app.models.tabular_anomaly_model import TabularAnomalyModel
from app.schemas.training import MetricRow
from app.services.anomaly_evaluation import AnomalyEvaluation


class AuditRegressionsTest(unittest.TestCase):
    def rows(self, count=100):
        return [
            MetricRow(
                timestamp=datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(minutes=i),
                deviceId="device",
                metric="power",
                value=float(i),
                label="NORMAL",
            )
            for i in range(count)
        ]

    def test_fit_preserves_every_observation_and_normal_labels_do_not_set_anomaly_fraction(self):
        model = TabularAnomalyModel()
        model.fit(self.rows(), {"features": {"power_mean": 49.5}, "featureSchemaHash": "test"})
        forest = model.pipeline.named_steps["model"]
        self.assertEqual(forest.max_samples_, 100)
        self.assertEqual(forest.contamination, "auto")

    def test_holdout_is_chronological_and_never_splits_equal_timestamps(self):
        evaluation = AnomalyEvaluation()
        rows = self.rows(10)
        train, validation = evaluation.split(rows + rows)
        self.assertLess(
            max(row.timestamp for row in train), min(row.timestamp for row in validation)
        )
        model = TabularAnomalyModel()
        model.fit(train, {"features": {}, "featureSchemaHash": "test"})
        metrics = evaluation.evaluate(model, train, validation)
        self.assertEqual(metrics["trainingRows"], len(train))
        self.assertEqual(metrics["validationRows"], len(validation))
        self.assertEqual(model.training_rows, len(train))

    def test_one_timestamp_does_not_claim_independent_validation(self):
        rows = self.rows(1) * 3
        evaluation = AnomalyEvaluation()
        train, validation = evaluation.split(rows)
        model = TabularAnomalyModel()
        model.fit(train, {"features": {}, "featureSchemaHash": "test"})
        self.assertNotIn("validationAnomalyScore", evaluation.evaluate(model, train, validation))

    def test_explicit_partitions_control_fit_and_test_rows_are_not_validation(self):
        rows = self.rows(12)
        for index, row in enumerate(rows):
            row.metadata["splitAssignment"] = (
                "TRAIN" if index < 6 else "VALIDATION" if index < 9 else "TEST"
            )
        train, validation = AnomalyEvaluation().split(list(reversed(rows)))
        self.assertEqual(train, rows[:6])
        self.assertEqual(validation, rows[6:9])
        rows[0].metadata.pop("splitAssignment")
        with self.assertRaises(ValueError):
            AnomalyEvaluation().split(rows)
