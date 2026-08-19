import tempfile
import unittest
from unittest.mock import Mock

from fastapi.testclient import TestClient

from app.api import training as training_api
from app.main import app
from app.schemas.training import TrainingRunResponse
from app.tests.settings_helpers import SettingsOverride


class MlInternalBoundaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self._training_service = training_api.training_service
        self._tempdir = tempfile.TemporaryDirectory()
        self._settings_override = SettingsOverride()
        self._settings_override.set(
            model_registry_path=self._tempdir.name,
            ml_artifact_store="local",
            ml_internal_auth_required=True,
            ml_internal_service_token="test-internal-token",
            ml_allowed_source_services="analytics-service",
            ml_max_request_bytes=4096,
        )
        training_api.training_service = Mock()
        self.client = TestClient(app)

    def tearDown(self) -> None:
        training_api.training_service = self._training_service
        self._settings_override.restore()
        self._tempdir.cleanup()

    def test_probe_endpoints_remain_open_without_internal_headers(self) -> None:
        health = self.client.get("/health")
        artifact_health = self.client.get("/health/artifacts")

        self.assertEqual(health.status_code, 200)
        self.assertEqual(artifact_health.status_code, 200)

    def test_training_request_without_token_is_rejected_before_training_runs(self) -> None:
        response = self.client.post("/train", json=self._training_payload())

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"]["code"], "missing_internal_service_token")
        training_api.training_service.start_training.assert_not_called()

    def test_invalid_token_is_rejected(self) -> None:
        response = self.client.post(
            "/inference",
            json=self._inference_payload(),
            headers={
                "X-Internal-Service-Token": "wrong-token",
                "X-Source-Service": "analytics-service",
            },
        )

        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.json()["detail"]["code"], "invalid_internal_service_token")

    def test_missing_source_service_is_rejected_when_allowlist_is_configured(self) -> None:
        response = self.client.post(
            "/inference",
            json=self._inference_payload(),
            headers={"X-Internal-Service-Token": "test-internal-token"},
        )

        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.json()["detail"]["code"], "source_service_forbidden")

    def test_authorized_inference_request_is_accepted(self) -> None:
        response = self.client.post(
            "/inference",
            json=self._inference_payload(),
            headers=self._authorized_headers(),
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["deviceId"], "device-1")

    def test_ml_aliases_use_the_same_internal_boundary(self) -> None:
        response = self.client.post(
            "/ml/inference",
            json=self._inference_payload(),
            headers=self._authorized_headers(),
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["deviceId"], "device-1")

    def test_metadata_endpoints_require_internal_headers(self) -> None:
        models = self.client.get("/models")
        run_lookup = self.client.get("/runs/run-1")

        self.assertEqual(models.status_code, 401)
        self.assertEqual(run_lookup.status_code, 401)

    def test_authorized_model_metadata_request_is_accepted(self) -> None:
        response = self.client.get("/models", headers=self._authorized_headers())

        self.assertEqual(response.status_code, 200)
        self.assertGreaterEqual(len(response.json()), 1)

    def test_oversized_training_body_is_rejected_before_training_runs(self) -> None:
        self._settings_override.set(ml_max_request_bytes=24)
        response = self.client.post(
            "/train",
            data="x" * 25,
            headers={**self._authorized_headers(), "Content-Type": "application/json"},
        )

        self.assertEqual(response.status_code, 413)
        self.assertEqual(response.json()["detail"]["code"], "request_body_too_large")
        training_api.training_service.start_training.assert_not_called()

    def test_validation_errors_are_sanitized(self) -> None:
        response = self.client.post(
            "/inference",
            json={"metrics": {"voltage": 230.0}},
            headers=self._authorized_headers(),
        )

        self.assertEqual(response.status_code, 400)
        self.assertIsInstance(response.json()["detail"], list)
        self.assertNotIn("input", response.json()["detail"][0])

    def test_authorized_training_alias_reaches_training_service(self) -> None:
        training_api.training_service.start_training.return_value = TrainingRunResponse.model_validate(
            {
                "runId": "run-1",
                "status": "SUCCEEDED",
                "datasetVersionId": "dataset-v1",
                "metrics": {},
            }
        )

        response = self.client.post(
            "/ml/train",
            json=self._training_payload(),
            headers=self._authorized_headers(),
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["runId"], "run-1")
        training_api.training_service.start_training.assert_called_once()

    def _authorized_headers(self) -> dict[str, str]:
        return {
            "X-Internal-Service-Token": "test-internal-token",
            "X-Source-Service": "analytics-service",
        }

    def _inference_payload(self) -> dict:
        return {
            "deviceId": "device-1",
            "metrics": {
                "voltage": 230.0,
                "activePowerW": 850.0,
            },
        }

    def _training_payload(self) -> dict:
        return {
            "datasetVersionId": "dataset-v1",
            "accountId": "account-1",
            "sourceType": "INTERNAL_TELEMETRY",
            "rows": [
                {
                    "timestamp": "2026-07-30T12:00:00Z",
                    "deviceId": "device-1",
                    "metric": "voltage",
                    "value": 230.0,
                }
            ],
        }


if __name__ == "__main__":
    unittest.main()
