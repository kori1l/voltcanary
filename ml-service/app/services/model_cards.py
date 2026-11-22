from typing import Any

from app.schemas.training import TrainingRequest


class ModelCardBuilder:
    def failure_card(
        self,
        model_payload: dict[str, Any],
        request: TrainingRequest,
        metrics: dict[str, float],
        evaluation_metadata: dict[str, Any],
        approval_metadata: dict[str, Any],
    ) -> dict[str, Any]:
        return {
            "modelCardVersion": "failure-prediction-model-card-v1",
            "modelVersionId": model_payload["modelVersionId"],
            "modelName": model_payload["name"],
            "modelVersion": model_payload["version"],
            "modelFamily": "FAILURE_PREDICTION",
            "algorithm": model_payload["algorithm"],
            "intendedUse": {
                "primaryUse": "Estimate device failure or corrective maintenance risk within the configured horizon.",
                "decisionBoundary": "Decision support only; not a component-level diagnosis or automatic shutdown command.",
                "predictionHorizonDays": model_payload["failureHorizonDays"],
            },
            "trainingData": {
                "datasetVersionId": request.dataset_version_id,
                "sourceType": request.source_type,
                "featureSchemaHash": model_payload["featureSchemaHash"],
                "trainingSamples": model_payload["trainingSamples"],
                "positiveLabels": model_payload["positiveLabels"],
                "negativeLabels": model_payload["negativeLabels"],
                "labelCoverage": metrics.get("labelCoverage", 0.0),
            },
            "target": model_payload["targetContract"],
            "evaluation": {
                **evaluation_metadata,
                "metrics": {
                    key: metrics[key]
                    for key in sorted(metrics)
                    if key.startswith("failure")
                    and (
                        key
                        not in {
                            "failureTargetSamples",
                            "failureUsableSamples",
                            "failurePositiveLabels",
                            "failureNegativeLabels",
                            "failureExcludedSamples",
                            "failurePositiveRate",
                            "failureClassImbalanceRatio",
                            "failureHorizonDays",
                        }
                    )
                },
            },
            "approvalGates": approval_metadata,
            "limitations": [
                "Requires outcome-derived labels with enough positive and negative examples.",
                "Risk scores are horizon-specific and should not be interpreted as exact failure dates.",
                "Performance can degrade when live feature schema or device population differs from training data.",
                "Rule-based risk remains separate from supervised failure risk.",
            ],
            "runtime": {
                "artifactContractVersion": model_payload["artifactContractVersion"],
                "scoring": model_payload["scoring"],
                "featureColumns": model_payload["featureColumns"],
            },
        }
