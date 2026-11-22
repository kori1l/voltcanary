import hashlib
import json
from typing import Any

from app.schemas.inference import InferenceRequest, InferenceResponse
from app.services.model_registry_service import MODEL_NAME, MODEL_STATUS, MODEL_TYPE, MODEL_VERSION


class InferenceResponseFactory:
    def input_hash(self, request: InferenceRequest) -> str:
        payload = request.model_dump(mode="json", by_alias=True)
        serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(serialized.encode("utf-8")).hexdigest()

    def feature_error_response(self, request: InferenceRequest) -> InferenceResponse | None:
        preference = request.model_preference
        if preference is None or preference.allow_baseline_heuristic:
            return None

        input_hash = self.input_hash(request)
        if request.feature_generation_status and request.feature_generation_status != "READY":
            details = request.feature_generation_errors or [
                "Online features are unavailable for the active prediction window."
            ]
            mismatch_statuses = {"FEATURE_CONTRACT_VERSION_MISMATCH", "FEATURE_SCHEMA_MISMATCH"}
            prediction_status = (
                "FEATURE_SCHEMA_MISMATCH"
                if request.feature_generation_status in mismatch_statuses
                or any("MISMATCH" in detail for detail in details)
                else "FEATURES_UNAVAILABLE"
            )
            return InferenceResponse(
                deviceId=request.device_id,
                modelVersionId=None,
                modelName=MODEL_NAME,
                modelVersion=MODEL_VERSION,
                modelType=MODEL_TYPE,
                modelStatus=MODEL_STATUS,
                predictionStatus=prediction_status,
                anomalyScore=0.0,
                degradationScore=0.0,
                riskLevel="LOW",
                confidence=0.1,
                signals=[],
                explanations=details,
                recommendedActions=[
                    "Regenerate online features with the selected model contract or select a compatible model."
                    if prediction_status == "FEATURE_SCHEMA_MISMATCH"
                    else "Regenerate the active feature window or enable baseline fallback."
                ],
                inputHash=input_hash,
            )

        required_contract_hash = preference.required_feature_contract_metadata_hash
        generated_contract_hash = str(request.feature_contract.get("contractMetadataHash") or "")
        required_hash = required_contract_hash or preference.required_feature_schema_hash
        generated_hash = generated_contract_hash or str(
            request.feature_contract.get("featureSchemaHash") or ""
        )
        if required_hash and generated_hash and required_hash != generated_hash:
            hash_name = "contract metadata" if required_contract_hash else "schema"
            return InferenceResponse(
                deviceId=request.device_id,
                modelVersionId=None,
                modelName=MODEL_NAME,
                modelVersion=MODEL_VERSION,
                modelType=MODEL_TYPE,
                modelStatus=MODEL_STATUS,
                predictionStatus="FEATURE_SCHEMA_MISMATCH",
                anomalyScore=0.0,
                degradationScore=0.0,
                riskLevel="LOW",
                confidence=0.1,
                signals=[],
                explanations=[
                    f"Online feature {hash_name} does not match the selected model feature contract."
                ],
                recommendedActions=[
                    "Select a compatible model version or regenerate features with the model contract."
                ],
                inputHash=input_hash,
            )
        return None

    def insufficient_data_response(self, request: InferenceRequest) -> InferenceResponse:
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=None,
            modelName=MODEL_NAME,
            modelVersion=MODEL_VERSION,
            modelType=MODEL_TYPE,
            modelStatus=MODEL_STATUS,
            predictionStatus="INSUFFICIENT_DATA",
            anomalyScore=0.0,
            degradationScore=0.0,
            riskLevel="LOW",
            confidence=0.2,
            signals=[],
            explanations=["Not enough telemetry input to produce a meaningful prediction."],
            recommendedActions=[
                "Collect additional telemetry before relying on ML prediction output."
            ],
            inputHash=self.input_hash(request),
        )

    def model_unavailable_response(
        self,
        request: InferenceRequest,
        model: dict[str, Any],
        reason: str,
    ) -> InferenceResponse:
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=model.get("modelVersionId"),
            modelName=model["name"],
            modelVersion=model["version"],
            modelType=model["modelType"],
            modelStatus=model["status"],
            predictionStatus="MODEL_UNAVAILABLE",
            anomalyScore=0.0,
            degradationScore=0.0,
            riskLevel="LOW",
            confidence=0.1,
            signals=[],
            explanations=[reason],
            recommendedActions=[
                "Verify model artifact storage and select another model version if the artifact is unavailable."
            ],
            inputHash=self.input_hash(request),
        )

    def model_incompatible_response(
        self, request: InferenceRequest, model: dict[str, Any]
    ) -> InferenceResponse:
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersionId=model.get("modelVersionId"),
            modelName=model["name"],
            modelVersion=model["version"],
            modelType=model["modelType"],
            modelStatus=model["status"],
            predictionStatus="MODEL_INCOMPATIBLE",
            anomalyScore=0.0,
            degradationScore=0.0,
            riskLevel="LOW",
            confidence=0.2,
            signals=[],
            explanations=[
                "Selected trained model has no compatible metric statistics for this request."
            ],
            recommendedActions=["Enable baseline fallback or select a compatible trained model."],
            inputHash=self.input_hash(request),
        )
