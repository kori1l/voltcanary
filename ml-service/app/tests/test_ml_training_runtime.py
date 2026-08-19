import unittest

from app.tests import test_ml_lifecycle as lifecycle_cases


class MlTrainingRuntimeTest(unittest.TestCase):
    setUp = lifecycle_cases.MlLifecycleTest.setUp
    tearDown = lifecycle_cases.MlLifecycleTest.tearDown

    test_feature_generation_is_deterministic_for_same_schema = (
        lifecycle_cases.MlLifecycleTest.test_feature_generation_is_deterministic_for_same_schema
    )
    test_training_persists_evaluation_metrics_and_artifact = (
        lifecycle_cases.MlLifecycleTest.test_training_persists_evaluation_metrics_and_artifact
    )
    test_training_rejects_empty_dataset = lifecycle_cases.MlLifecycleTest.test_training_rejects_empty_dataset
    test_training_retry_with_same_idempotency_key_returns_existing_artifact = (
        lifecycle_cases.MlLifecycleTest.test_training_retry_with_same_idempotency_key_returns_existing_artifact
    )
    test_training_cancellation_before_artifact_promotion_returns_canceled = (
        lifecycle_cases.MlLifecycleTest.test_training_cancellation_before_artifact_promotion_returns_canceled
    )
    test_training_timeout_before_artifact_promotion_returns_failed = (
        lifecycle_cases.MlLifecycleTest.test_training_timeout_before_artifact_promotion_returns_failed
    )
    test_partial_artifact_bundle_is_removed_when_verification_fails = (
        lifecycle_cases.MlLifecycleTest.test_partial_artifact_bundle_is_removed_when_verification_fails
    )
    test_failure_target_builder_constructs_horizon_targets_from_generated_labels = (
        lifecycle_cases.MlLifecycleTest.test_failure_target_builder_constructs_horizon_targets_from_generated_labels
    )
    test_known_failure_fixture_dataset_has_expected_outcomes = (
        lifecycle_cases.MlLifecycleTest.test_known_failure_fixture_dataset_has_expected_outcomes
    )
    test_failure_training_fits_logistic_baseline_and_persists_artifact = (
        lifecycle_cases.MlLifecycleTest.test_failure_training_fits_logistic_baseline_and_persists_artifact
    )
    test_failure_training_fits_random_forest_candidate = (
        lifecycle_cases.MlLifecycleTest.test_failure_training_fits_random_forest_candidate
    )
    test_failure_training_stores_time_based_evaluation_metrics = (
        lifecycle_cases.MlLifecycleTest.test_failure_training_stores_time_based_evaluation_metrics
    )


if __name__ == "__main__":
    unittest.main()
