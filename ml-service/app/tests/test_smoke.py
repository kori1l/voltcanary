import unittest

from fastapi.testclient import TestClient

from app.config.settings import settings
from app.main import app


class MlServiceSmokeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._auth_required = settings.ml_internal_auth_required
        settings.ml_internal_auth_required = False
        self.client = TestClient(app)

    def tearDown(self) -> None:
        settings.ml_internal_auth_required = self._auth_required

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

    def test_model_only_inference_returns_features_unavailable_when_online_features_are_missing(self) -> None:
        response = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {},
                "modelPreference": {"allowBaselineHeuristic": False},
                "featureGenerationStatus": "FEATURES_UNAVAILABLE",
                "featureGenerationErrors": ["MISSING_REQUIRED_FEATURE: metricName"],
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["predictionStatus"], "FEATURES_UNAVAILABLE")
        self.assertIn("MISSING_REQUIRED_FEATURE: metricName", response.json()["explanations"])

    def test_model_only_inference_returns_schema_mismatch_for_incompatible_online_features(self) -> None:
        response = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {"activePowerW": 850.0},
                "modelPreference": {
                    "allowBaselineHeuristic": False,
                    "requiredFeatureSchemaHash": "sha256:model-schema",
                },
                "featureGenerationStatus": "READY",
                "featureContract": {"featureSchemaHash": "sha256:online-schema"},
                "featureVector": {"metricName": "activePowerW"},
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["predictionStatus"], "FEATURE_SCHEMA_MISMATCH")
        self.assertIn("schema", response.json()["explanations"][0])

    def test_model_only_inference_prefers_contract_metadata_hash_for_feature_parity(self) -> None:
        response = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {"activePowerW": 850.0},
                "modelPreference": {
                    "allowBaselineHeuristic": False,
                    "requiredFeatureSchemaHash": "sha256:offline-full-schema",
                    "requiredFeatureContractMetadataHash": "sha256:contract-v1",
                },
                "featureGenerationStatus": "READY",
                "featureContract": {
                    "featureSchemaHash": "sha256:online-runtime-schema",
                    "contractMetadataHash": "sha256:contract-v1",
                },
                "featureVector": {"activePowerW": 850.0},
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertNotEqual(response.json()["predictionStatus"], "FEATURE_SCHEMA_MISMATCH")

    def test_model_only_inference_returns_schema_mismatch_for_contract_metadata_hash_mismatch(self) -> None:
        response = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {"activePowerW": 850.0},
                "modelPreference": {
                    "allowBaselineHeuristic": False,
                    "requiredFeatureContractMetadataHash": "sha256:trained-contract",
                },
                "featureGenerationStatus": "READY",
                "featureContract": {"contractMetadataHash": "sha256:online-contract"},
                "featureVector": {"activePowerW": 850.0},
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["predictionStatus"], "FEATURE_SCHEMA_MISMATCH")
        self.assertIn("contract metadata", response.json()["explanations"][0])

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
