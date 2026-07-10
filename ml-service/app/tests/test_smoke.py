import unittest

from fastapi.testclient import TestClient

from app.main import app


class MlServiceSmokeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)

    def test_health_returns_200(self) -> None:
        response = self.client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")

    def test_inference_returns_low_risk_for_normal_telemetry(self) -> None:
        response = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {
                    "voltage": 230.5,
                    "current": 5.0,
                    "activePowerW": 850.0,
                    "energyGrowth": 1.0,
                },
                "baseline": {
                    "metric": "activePowerW",
                    "mean": 800.0,
                    "min": 700.0,
                    "max": 950.0,
                    "sampleCount": 24,
                },
                "recentWindow": {
                    "sampleCount": 24,
                    "excludedSampleCount": 0,
                },
            },
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["modelVersion"], "baseline-heuristic-v1")
        self.assertEqual(body["predictionStatus"], "BASELINE_HEURISTIC")
        self.assertIn(body["riskLevel"], ["LOW", "MEDIUM"])
        self.assertGreaterEqual(len(body["explanations"]), 1)
        self.assertIn("inputHash", body)

    def test_inference_returns_high_risk_for_anomalous_telemetry(self) -> None:
        normal = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {"voltage": 230.0, "current": 5.0, "activePowerW": 850.0},
            },
        )
        anomalous = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {"voltage": 275.0, "current": 38.0, "activePowerW": 6500.0},
            },
        )

        self.assertEqual(anomalous.status_code, 200)
        self.assertGreater(anomalous.json()["anomalyScore"], normal.json()["anomalyScore"])
        self.assertIn(anomalous.json()["riskLevel"], ["MEDIUM", "HIGH", "CRITICAL"])
        self.assertGreaterEqual(len(anomalous.json()["signals"]), 1)

    def test_inference_marks_empty_metrics_as_insufficient_data(self) -> None:
        response = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {},
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["predictionStatus"], "INSUFFICIENT_DATA")
        self.assertLess(response.json()["confidence"], 0.5)

    def test_inference_rejects_non_finite_metric_values(self) -> None:
        response = self.client.post(
            "/inference",
            data='{"deviceId":"device-1","metrics":{"voltage":NaN}}',
            headers={"Content-Type": "application/json"},
        )

        self.assertEqual(response.status_code, 400)

    def test_models_returns_baseline_heuristic_readiness(self) -> None:
        response = self.client.get("/models")

        self.assertEqual(response.status_code, 200)
        self.assertGreaterEqual(len(response.json()), 1)
        self.assertEqual(response.json()[0]["name"], "voltcanary-baseline-heuristic")
        self.assertEqual(response.json()[0]["version"], "baseline-heuristic-v1")
        self.assertEqual(response.json()[0]["status"], "READY_HEURISTIC")


if __name__ == "__main__":
    unittest.main()
