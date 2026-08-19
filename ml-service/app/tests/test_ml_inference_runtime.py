import unittest

from app.tests import test_ml_lifecycle as lifecycle_cases


class MlInferenceRuntimeTest(unittest.TestCase):
    setUp = lifecycle_cases.MlLifecycleTest.setUp
    tearDown = lifecycle_cases.MlLifecycleTest.tearDown
    _artifact_request = lifecycle_cases.MlLifecycleTest._artifact_request

    test_failure_model_inference_returns_failure_risk_fields = (
        lifecycle_cases.MlLifecycleTest.test_failure_model_inference_returns_failure_risk_fields
    )
    test_failure_model_inference_scores_online_feature_vector = (
        lifecycle_cases.MlLifecycleTest.test_failure_model_inference_scores_online_feature_vector
    )
    test_serving_artifact_cache_reuses_loaded_failure_pipeline = (
        lifecycle_cases.MlLifecycleTest.test_serving_artifact_cache_reuses_loaded_failure_pipeline
    )
    test_failure_model_inference_loads_by_model_version_id_from_registry = (
        lifecycle_cases.MlLifecycleTest.test_failure_model_inference_loads_by_model_version_id_from_registry
    )
    test_failure_model_inference_returns_features_unavailable_for_missing_feature_columns = (
        lifecycle_cases.MlLifecycleTest.test_failure_model_inference_returns_features_unavailable_for_missing_feature_columns
    )
    test_failure_model_inference_returns_insufficient_labels_from_artifact_gates = (
        lifecycle_cases.MlLifecycleTest.test_failure_model_inference_returns_insufficient_labels_from_artifact_gates
    )
    test_inference_loads_model_from_artifact_metadata_without_registry = (
        lifecycle_cases.MlLifecycleTest.test_inference_loads_model_from_artifact_metadata_without_registry
    )
    test_serving_artifact_cache_reuses_loaded_anomaly_pipeline = (
        lifecycle_cases.MlLifecycleTest.test_serving_artifact_cache_reuses_loaded_anomaly_pipeline
    )
    test_serving_artifact_cache_reloads_when_artifact_hash_changes = (
        lifecycle_cases.MlLifecycleTest.test_serving_artifact_cache_reloads_when_artifact_hash_changes
    )
    test_serving_artifact_cache_does_not_cache_corrupted_artifact_loads = (
        lifecycle_cases.MlLifecycleTest.test_serving_artifact_cache_does_not_cache_corrupted_artifact_loads
    )
    test_serving_artifact_cache_evicts_least_recent_entry_at_capacity = (
        lifecycle_cases.MlLifecycleTest.test_serving_artifact_cache_evicts_least_recent_entry_at_capacity
    )
    test_inference_returns_model_unavailable_when_runtime_artifact_is_missing = (
        lifecycle_cases.MlLifecycleTest.test_inference_returns_model_unavailable_when_runtime_artifact_is_missing
    )


if __name__ == "__main__":
    unittest.main()
