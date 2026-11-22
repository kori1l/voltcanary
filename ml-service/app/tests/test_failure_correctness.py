import unittest
from datetime import datetime, timedelta, timezone

from app.models.failure_evaluation import FailurePredictionEvaluator
from app.models.failure_prediction_model import FailurePredictionModel
from app.models.failure_target import FailureTargetSample


class FailureCorrectnessTest(unittest.TestCase):
    def samples(self):
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        return [
            FailureTargetSample(
                str(i),
                "device",
                start + timedelta(days=i),
                7,
                {"power": value, "temperature": value / 10},
                int(value > 25),
                None,
                None,
                None,
            )
            for i, value in enumerate([10.0, 20.0, 30.0, 40.0])
        ]

    def test_explanations_reconstruct_the_actual_logit_and_preserve_negative_contributions(self):
        model = FailurePredictionModel()
        model.fit(self.samples())
        for features in ({"power": 20.0, "temperature": 2.0}, {"power": 20.0}):
            with self.subTest(features=features):
                drivers = model.risk_drivers({**features, "unknown": 1_000_000}, limit=100)
                expected = float(model.pipeline.decision_function([features])[0])
                reconstructed = sum(driver["contribution"] for driver in drivers)
                reconstructed += float(model.pipeline.named_steps["model"].intercept_[0])
                self.assertAlmostEqual(expected, reconstructed, places=5)
                self.assertTrue(all(driver["direction"] == "decreases_risk" for driver in drivers))
                self.assertNotIn("unknown", {driver["feature"] for driver in drivers})

    def test_one_observation_time_cannot_claim_independent_holdout(self):
        samples = self.samples()
        from dataclasses import replace

        samples = [replace(sample, prediction_at=samples[0].prediction_at) for sample in samples]
        self.assertIsNone(FailurePredictionEvaluator().time_based_split(samples))

    def test_duplicate_observation_times_stay_on_one_side_of_holdout(self):
        from dataclasses import replace

        samples = self.samples()
        # Both classes before the boundary; multiple devices at each timestamp.
        samples = [replace(sample, target=i % 2) for i, sample in enumerate(samples)]
        samples += [
            replace(sample, sample_id="copy-" + sample.sample_id, device_id="other")
            for sample in samples
        ]
        split = FailurePredictionEvaluator().time_based_split(list(reversed(samples)))
        self.assertIsNotNone(split)
        self.assertLess(
            max(sample.prediction_at for sample in split.train_samples),
            min(sample.prediction_at for sample in split.validation_samples),
        )
