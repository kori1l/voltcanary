from app.schemas.inference import InferenceRequest, InferenceResponse
from app.services.artifact_store import ArtifactStore
from app.services.inference_runtime import (
    AnomalyModelScorer,
    BaselineHeuristicScorer,
    FailureModelScorer,
    InferenceResponseFactory,
    MetricValueExtractor,
    TrainedModelResolver,
)
from app.services.serving_cache import ServingArtifactCache


class InferenceService:
    def __init__(
        self,
        artifact_store: ArtifactStore | None = None,
        serving_cache: ServingArtifactCache | None = None,
    ) -> None:
        self.artifact_store = artifact_store or ArtifactStore()
        self.serving_cache = serving_cache or ServingArtifactCache(self.artifact_store)
        self.extractor = MetricValueExtractor()
        self.response_factory = InferenceResponseFactory()
        self.model_resolver = TrainedModelResolver(self.extractor)
        self.baseline_scorer = BaselineHeuristicScorer(self.extractor, self.response_factory)
        self.anomaly_scorer = AnomalyModelScorer(self.response_factory)
        self.failure_scorer = FailureModelScorer(self.response_factory)

    def predict(self, request: InferenceRequest) -> InferenceResponse:
        feature_error_response = self.response_factory.feature_error_response(request)
        if feature_error_response is not None:
            return feature_error_response

        trained_response = self._predict_with_trained_model(request)
        if trained_response is not None:
            return trained_response

        return self.baseline_scorer.predict(request)

    def _predict_with_trained_model(self, request: InferenceRequest) -> InferenceResponse | None:
        model = self.model_resolver.resolve(request.model_preference)
        if model is None:
            return None

        metric_values = self.model_resolver.trained_model_features(request)
        if not metric_values:
            return None

        try:
            runtime = self.serving_cache.get(model)
        except (OSError, ValueError, ImportError) as exc:
            return self.response_factory.model_unavailable_response(request, model, str(exc))

        artifact = runtime.artifact
        self.model_resolver.apply_runtime_metadata(model, artifact, runtime.model_type)
        if model["modelType"] == "trained_failure_prediction":
            return self.failure_scorer.predict(request, model, artifact, runtime.pipeline, metric_values)
        return self.anomaly_scorer.predict(request, model, artifact, runtime.pipeline, metric_values)
