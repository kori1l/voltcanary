class TrainingService:
    def start_training(self) -> dict[str, str]:
        return {
            "status": "accepted",
            "runId": "mock-run-001",
            "message": "training endpoint is not implemented yet",
        }

    def get_run(self, run_id: str) -> dict[str, str]:
        return {
            "runId": run_id,
            "status": "mock",
            "message": "training run lookup is not implemented yet",
        }
