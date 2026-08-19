import unittest

from app.tests import test_ml_lifecycle as lifecycle_cases


class MlLifecycleApiTest(unittest.TestCase):
    setUp = lifecycle_cases.MlLifecycleTest.setUp
    tearDown = lifecycle_cases.MlLifecycleTest.tearDown

    test_feature_artifact_loading_rejects_schema_drift = (
        lifecycle_cases.MlLifecycleTest.test_feature_artifact_loading_rejects_schema_drift
    )
    test_artifact_store_verifies_model_bundle_hashes = (
        lifecycle_cases.MlLifecycleTest.test_artifact_store_verifies_model_bundle_hashes
    )
    test_s3_artifact_store_round_trips_model_bundle = (
        lifecycle_cases.MlLifecycleTest.test_s3_artifact_store_round_trips_model_bundle
    )
    test_api_rejects_conflicting_retry_for_same_idempotency_key = (
        lifecycle_cases.MlLifecycleTest.test_api_rejects_conflicting_retry_for_same_idempotency_key
    )
    test_api_trains_external_dataset_and_routes_inference_to_trained_model = (
        lifecycle_cases.MlLifecycleTest.test_api_trains_external_dataset_and_routes_inference_to_trained_model
    )
    test_api_training_run_lookup_returns_created_run = (
        lifecycle_cases.MlLifecycleTest.test_api_training_run_lookup_returns_created_run
    )


if __name__ == "__main__":
    unittest.main()
