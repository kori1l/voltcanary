import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from .api import ApiError
from .conversations import ConversationRunner


class ConversationRunnerTest(unittest.TestCase):
    def runner(self, directory, transport_failure=False):
        api = Mock()

        def call(method, path, body=None):
            if method == "POST" and path == "/ai-agent/conversations":
                return {}
            if method == "GET" and "/messages?" in path:
                return {"items": [{"id": "input", "text": "ordinary request"}], "nextCursor": None}
            if method == "GET" and path.endswith("/trace/export"):
                return [{"stage": "MODEL_CALL", "status": "STARTED"}, {"stage": "MODEL_CALL", "status": "FAILED", "code": "PROVIDER_FAILED"}]
            if method == "GET":
                return {"conversation": {"revision": 0}}
            if transport_failure:
                raise ApiError(503, "DOWNSTREAM_UNAVAILABLE")
            return {"plan": {"planId": "review", "planningStatus": "READY", "actions": []}, "pendingStatus": "COMPLETED"}

        api.call.side_effect = call
        return ConversationRunner(api, {"providerId": "provider", "modelConfigId": "model"}, Path(directory))

    def test_persists_history_and_actual_calls_for_success_and_reuses_conversation(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(directory)
            plan = runner.model("Create dashboard", None, ["device"])
            runner.model("Move cards", plan, ["device"])
            self.assertEqual(2, len(runner.diagnostics))
            self.assertEqual(runner.diagnostics[0]["conversationId"], runner.diagnostics[1]["conversationId"])
            self.assertEqual(1, runner.diagnostics[0]["modelCalls"])
            self.assertEqual(2, len(list(Path(directory).glob("conversations/*/*.json"))))
            calls = [args for args, _ in runner.api.call.call_args_list if args[0] == "POST" and args[1].endswith("/messages")]
            self.assertEqual("review", calls[1][2]["parentPlanId"])

    def test_collects_history_and_trace_even_when_message_transport_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(directory, transport_failure=True)
            with self.assertRaises(ApiError):
                runner.model("Create dashboard", None, ["device"])
            self.assertEqual("TRANSPORT_FAILURE", runner.diagnostics[0]["pendingStatus"])
            self.assertEqual("PROVIDER_FAILED", runner.diagnostics[0]["failures"][0]["code"])
            self.assertEqual(1, len(list(Path(directory).glob("conversations/*/*.json"))))

    def test_independent_branches_have_distinct_conversations_and_the_same_explicit_base(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(directory)
            parent = runner.model("Create a chart", None, ["device"])
            runner.branch("Change its title", parent, ["device"])
            runner.branch("Change its period", parent, ["device"])
            self.assertEqual(3, len({d["conversationId"] for d in runner.diagnostics}))
            posts = [args for args, _ in runner.api.call.call_args_list if args[0] == "POST" and args[1].endswith("/messages")]
            self.assertEqual(["review", "review"], [p[2]["parentPlanId"] for p in posts[1:]])

    def test_recovery_reads_retained_review_without_promoting_the_failed_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(directory)
            runner.conversations["failed"] = "conversation"
            failed = {"planId": "failed", "planningStatus": "FAILED"}
            detail = {"plan": failed, "pendingStatus": "FAILED", "acceptedReview": {"planId": "accepted", "planningStatus": "READY"}}
            runner.api.call.side_effect = None
            runner.api.call.return_value = detail
            restored = runner.recover(failed)
            self.assertEqual(restored["planId"], "accepted")
            self.assertTrue(restored["_evaluationReadOnlyAcceptedReview"])
            self.assertEqual(detail["plan"]["planningStatus"], "FAILED")
            self.assertEqual(runner.api.call.call_count, 1)


if __name__ == "__main__":
    unittest.main()
