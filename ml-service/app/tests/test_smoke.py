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

    def test_inference_returns_mock_model_version(self) -> None:
        response = self.client.post(
            "/inference",
            json={
                "deviceId": "device-1",
                "metrics": {
                    "voltage": 230.5,
                },
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["modelVersion"], "mock-model-v1")

    def test_models_returns_mock_models(self) -> None:
        response = self.client.get("/models")

        self.assertEqual(response.status_code, 200)
        self.assertGreaterEqual(len(response.json()), 1)
        self.assertEqual(response.json()[0]["version"], "mock-model-v1")


if __name__ == "__main__":
    unittest.main()
