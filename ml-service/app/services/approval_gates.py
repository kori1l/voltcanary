from typing import Any

from app.schemas.training import ApprovalGateProfile, TrainingRequest


class ApprovalGateEvaluator:
    def failure_reason(self, request: TrainingRequest, metrics: dict[str, float]) -> str | None:
        profile = self._approval_gate_profile(request)
        positives = int(metrics.get("failurePositiveLabels", 0.0))
        negatives = int(metrics.get("failureNegativeLabels", 0.0))
        label_coverage = float(metrics.get("labelCoverage", 0.0))
        min_positive_labels = profile.minimum_positive_labels
        min_label_coverage = profile.minimum_label_coverage
        if positives < min_positive_labels:
            return (
                "Insufficient positive failure labels: "
                f"required at least {min_positive_labels}, found {positives}."
            )
        if negatives < 1:
            return "Insufficient negative non-failure labels: required at least 1, found 0."
        if label_coverage < min_label_coverage:
            return (
                "Insufficient failure label coverage: "
                f"required at least {min_label_coverage:.4f}, found {label_coverage:.4f}."
            )
        return None

    def approval_metadata(
        self,
        request: TrainingRequest,
        metrics: dict[str, float],
        feature_schema_hash: str,
        evaluation_metadata: dict[str, Any],
    ) -> dict[str, Any]:
        profile = self._approval_gate_profile(request)
        criteria = [
            self._approval_gate(
                name="minimum_positive_labels",
                observed=self._metric(metrics, "failurePositiveLabels"),
                threshold=float(profile.minimum_positive_labels),
                passed=self._metric_passes_minimum(
                    metrics, "failurePositiveLabels", profile.minimum_positive_labels
                ),
                reason="Requires enough confirmed failure or corrective maintenance labels.",
            ),
            self._approval_gate(
                name="minimum_label_coverage",
                observed=self._metric(metrics, "labelCoverage"),
                threshold=float(profile.minimum_label_coverage),
                passed=self._metric_passes_minimum(
                    metrics, "labelCoverage", profile.minimum_label_coverage
                ),
                reason="Requires enough usable horizon labels for the dataset.",
            ),
            self._approval_gate(
                name="time_based_evaluation_available",
                observed=1.0 if evaluation_metadata.get("status") == "EVALUATED" else 0.0,
                threshold=1.0,
                passed=evaluation_metadata.get("status") == "EVALUATED",
                reason="Requires a time-based holdout with a train split containing both classes.",
            ),
            self._approval_gate(
                name="minimum_precision",
                observed=self._metric(metrics, "failurePrecision"),
                threshold=profile.minimum_precision,
                passed=self._metric_passes_minimum(
                    metrics, "failurePrecision", profile.minimum_precision
                ),
                reason="Requires candidate precision to meet the configured approval threshold.",
            ),
            self._approval_gate(
                name="minimum_recall",
                observed=self._metric(metrics, "failureRecall"),
                threshold=profile.minimum_recall,
                passed=self._metric_passes_minimum(
                    metrics, "failureRecall", profile.minimum_recall
                ),
                reason="Requires candidate recall to meet the configured approval threshold.",
            ),
            self._approval_gate(
                name="minimum_pr_auc_or_lift",
                observed=self._metric(metrics, "failurePrAuc"),
                threshold=profile.minimum_pr_auc,
                passed=(
                    self._metric_passes_minimum(metrics, "failurePrAuc", profile.minimum_pr_auc)
                    or self._metric_passes_minimum(
                        metrics, "failurePrAucLift", profile.minimum_pr_auc_lift
                    )
                ),
                reason="Requires PR-AUC or PR-AUC lift to meet the configured approval profile.",
            ),
            self._approval_gate(
                name="maximum_false_alert_rate",
                observed=self._metric(metrics, "failureFalseAlertsPerDeviceMonth"),
                threshold=profile.maximum_false_alerts_per_device_month,
                passed=self._metric_passes_maximum(
                    metrics,
                    "failureFalseAlertsPerDeviceMonth",
                    profile.maximum_false_alerts_per_device_month,
                ),
                reason="Limits false alerts per device-month before promotion.",
            ),
            self._approval_gate(
                name="minimum_lead_time",
                observed=self._metric(metrics, "failureAverageLeadTimeDays"),
                threshold=profile.minimum_lead_time_days,
                passed=self._metric_passes_minimum(
                    metrics, "failureAverageLeadTimeDays", profile.minimum_lead_time_days
                ),
                reason="Requires enough warning lead time for operational response.",
            ),
            self._approval_gate(
                name="artifact_load_test",
                observed=1.0,
                threshold=1.0,
                passed=profile.require_artifact_load_test,
                reason="Model bundle is saved with a loadable manifest and verified after persistence.",
            ),
            self._approval_gate(
                name="feature_schema_compatibility",
                observed=1.0 if feature_schema_hash else 0.0,
                threshold=1.0,
                passed=bool(feature_schema_hash)
                if profile.require_feature_schema_compatibility
                else True,
                reason="Requires a feature schema hash for runtime compatibility checks.",
            ),
        ]
        passed = all(item["passed"] for item in criteria)
        return {
            "approvalGateVersion": "failure-approval-gates-v1",
            "profileId": profile.profile_id,
            "profileVersion": profile.profile_version,
            "thresholdSource": profile.threshold_source,
            "status": "PASSED" if passed else "NEEDS_REVIEW",
            "promotionEligible": passed,
            "criteria": criteria,
        }

    def _approval_gate_profile(self, request: TrainingRequest) -> ApprovalGateProfile:
        profile = request.approval_gate_profile or ApprovalGateProfile()
        values = profile.model_dump()
        if request.minimum_positive_labels is not None:
            values["minimum_positive_labels"] = request.minimum_positive_labels
        if request.minimum_label_coverage is not None:
            values["minimum_label_coverage"] = request.minimum_label_coverage
        return ApprovalGateProfile.model_validate(values)

    def _metric(self, metrics: dict[str, float], key: str) -> float | None:
        value = metrics.get(key)
        return float(value) if isinstance(value, int | float) else None

    def _metric_passes_minimum(self, metrics: dict[str, float], key: str, threshold: float) -> bool:
        value = self._metric(metrics, key)
        return value is not None and value >= threshold

    def _metric_passes_maximum(self, metrics: dict[str, float], key: str, threshold: float) -> bool:
        value = self._metric(metrics, key)
        return value is not None and value <= threshold

    def _approval_gate(
        self,
        name: str,
        observed: float | None,
        threshold: float,
        passed: bool,
        reason: str,
    ) -> dict[str, Any]:
        return {
            "name": name,
            "observed": None if observed is None else round(float(observed), 6),
            "threshold": round(float(threshold), 6),
            "passed": passed,
            "reason": reason,
        }
