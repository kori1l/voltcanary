from app.schemas.inference import InferenceRequest, InferenceResponse


class InferenceService:
    def predict(self, request: InferenceRequest) -> InferenceResponse:
        return InferenceResponse(
            deviceId=request.device_id,
            modelVersion="mock-model-v1",
            anomalyScore=0.12,
            degradationScore=0.08,
            riskLevel="LOW",
            confidence=0.5,
            explanations=["Mock inference result"],
        )
