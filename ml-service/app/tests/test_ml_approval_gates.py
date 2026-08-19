import unittest

from app.tests import test_ml_lifecycle as lifecycle_cases


class MlApprovalGateTest(unittest.TestCase):
    setUp = lifecycle_cases.MlLifecycleTest.setUp
    tearDown = lifecycle_cases.MlLifecycleTest.tearDown

    test_failure_training_applies_custom_approval_gate_profile = (
        lifecycle_cases.MlLifecycleTest.test_failure_training_applies_custom_approval_gate_profile
    )
    test_failure_training_rejects_insufficient_positive_labels = (
        lifecycle_cases.MlLifecycleTest.test_failure_training_rejects_insufficient_positive_labels
    )
    test_failure_training_rejects_missing_negative_class = (
        lifecycle_cases.MlLifecycleTest.test_failure_training_rejects_missing_negative_class
    )
    test_failure_training_rejects_low_label_coverage_gate = (
        lifecycle_cases.MlLifecycleTest.test_failure_training_rejects_low_label_coverage_gate
    )


if __name__ == "__main__":
    unittest.main()
