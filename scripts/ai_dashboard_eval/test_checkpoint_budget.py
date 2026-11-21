import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

class CheckpointBudgetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / "ai-first-pass-checkpoint.py"
        spec = importlib.util.spec_from_file_location("a5_checkpoint_test", path)
        cls.module = importlib.util.module_from_spec(spec)
        sys.path.insert(0, str(path.parent))
        try:
            spec.loader.exec_module(cls.module)
        finally:
            sys.path.pop(0)

    def test_checkpoint_requires_evidenced_first_pass_on_each_model_turn(self):
        cases = [{"id": "dashboard", "turns": [
            {"id": "create", "action": "message"}, {"id": "edit", "action": "message"},
            {"id": "reload", "action": "reload"},
        ]}]
        turns = [
            {"id": "create", "passed": True, "firstTryPassed": True},
            {"id": "edit", "passed": True, "firstTryPassed": None},
            {"id": "reload", "passed": True, "firstTryPassed": None},
        ]
        results = [{"caseId": "dashboard", "passed": True, "turns": turns}]
        self.assertFalse(self.module.diagnostic_passed(results, cases))
        turns[1]["firstTryPassed"] = False
        self.assertFalse(self.module.diagnostic_passed(results, cases))
        turns[1]["firstTryPassed"] = True
        self.assertTrue(self.module.diagnostic_passed(results, cases))
        self.assertFalse(self.module.diagnostic_passed(results * 2, cases))
        turns[2]["passed"] = False
        self.assertFalse(self.module.diagnostic_passed(results, cases))
        turns[2]["passed"] = True
        del turns[0]
        self.assertFalse(self.module.diagnostic_passed(results, cases))

    def test_linked_campaign_reserves_a_full_turn_under_the_original_shared_ceiling(self):
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            runner = self.module.DiagnosticRunner(api, {}, Path(directory), previous_usage={
                "calls": 39, "inputTokens": 0, "outputTokens": 0, "reservedCostMicros": 0})
            with self.assertRaises(self.module.ApiError) as failure:
                runner.model("request", None, [])
            self.assertEqual("CAMPAIGN_BUDGET_CEILING", failure.exception.code)
            api.assert_not_called()
            self.assertEqual([], api.mock_calls)

    def test_partial_campaign_keeps_spend_available_for_followup(self):
        runner = Mock(results=[], halted=None, diagnostics=[{"callAccounting": {"complete": True, "calls": [
            {"estimatedInputTokens": 12, "reservedOutputTokens": 8, "reservedCostMicros": 2}
        ]}}])
        report = {}
        self.module.record_campaign(report, runner, [{"id": "unreached"}], {
            "calls": 4, "inputTokens": 20, "outputTokens": 10, "reservedCostMicros": 6,
        })
        self.assertEqual({"calls": 5, "inputTokens": 32, "outputTokens": 18, "reservedCostMicros": 8},
                         report["cumulativeReservedUsage"])
        self.assertEqual(runner.diagnostics, report["diagnostics"])
        self.assertFalse(report["qualified"])
        runner.diagnostics.append({"callAccounting": {"complete": False, "calls": []}})
        self.module.record_campaign(report, runner, [{"id": "unreached"}], {})
        self.assertEqual({key: None for key in self.module.TURN_RESERVATION}, report["cumulativeReservedUsage"])

    def test_linked_campaign_cannot_reset_the_expired_wall_clock_or_call_the_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            runner = self.module.DiagnosticRunner(api, {}, Path(directory), previous_elapsed=1200)
            with self.assertRaises(self.module.ApiError) as failure:
                runner.model("request", None, [])
            self.assertEqual("CAMPAIGN_TIME_CEILING", failure.exception.code)
            self.assertEqual([], api.mock_calls)

    def test_known_zero_call_rejection_does_not_halt_the_next_independent_case(self):
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            api.call.return_value = {"enabled": True, "profile": "FIRST_PASS_DIAGNOSTIC", "automaticCorrections": False, "modelJudge": False}
            runner = self.module.DiagnosticRunner(api, {}, Path(directory))
            result = {"_evaluationCallAccounting": {"complete": True, "modelCalls": 0, "repairCalls": 0, "assessmentCalls": 0}}
            with patch.object(self.module.ConversationRunner, "model", return_value=result) as model:
                runner.model("first rejected before provider", None, [])
                runner.model("second independent request", None, [])
                self.assertEqual(2, model.call_count)
                self.assertIsNone(runner.halted)

    def test_diagnostic_mode_rejects_hidden_assessment(self):
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            api.call.return_value = {"enabled": True, "profile": "FIRST_PASS_DIAGNOSTIC", "automaticCorrections": False, "modelJudge": False}
            runner = self.module.DiagnosticRunner(api, {}, Path(directory))
            result = {"_evaluationCallAccounting": {"complete": True, "modelCalls": 2, "repairCalls": 0, "assessmentCalls": 1}}
            with patch.object(self.module.ConversationRunner, "model", return_value=result):
                with self.assertRaises(self.module.ApiError): runner.model("request", None, [])

    def test_missing_trace_after_failed_turn_stops_before_next_request(self):
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            api.call.return_value = {"enabled": True, "profile": "FIRST_PASS_DIAGNOSTIC",
                                     "automaticCorrections": False, "modelJudge": False}
            runner = self.module.DiagnosticRunner(api, {}, Path(directory))
            with patch.object(self.module.ConversationRunner, "model",
                              side_effect=self.module.ApiError(0, "TRANSPORT_FAILURE")) as model:
                for message in ("failed request", "next request"):
                    with self.assertRaises(self.module.ApiError) as failure:
                        runner.model(message, None, [])
                    self.assertEqual("DIAGNOSTIC_ACCOUNTING_INCOMPLETE", failure.exception.code)
                model.assert_called_once()

    def test_standard_allows_correction_but_forbids_a_hidden_judge(self):
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            api.call.return_value = {"enabled": True, "profile": "STANDARD", "automaticCorrections": True, "modelJudge": False}
            runner = self.module.DiagnosticRunner(api, {}, Path(directory), expected_profile="STANDARD")
            result = {"_evaluationCallAccounting": {"complete": True, "modelCalls": 3, "repairCalls": 1, "assessmentCalls": 0}}
            with patch.object(self.module.ConversationRunner, "model", return_value=result):
                self.assertEqual(result, runner.model("request", None, []))
                result["_evaluationCallAccounting"]["assessmentCalls"] = 1
                with self.assertRaises(self.module.ApiError): runner.model("request", None, [])

    def test_standard_rejects_mismatched_runtime_flags_before_model_call(self):
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            api.call.return_value = {"enabled": True, "profile": "STANDARD", "automaticCorrections": True, "modelJudge": True}
            runner = self.module.DiagnosticRunner(api, {}, Path(directory), expected_profile="STANDARD")
            with patch.object(self.module.ConversationRunner, "model") as model:
                with self.assertRaises(self.module.ApiError): runner.model("request", None, [])
                model.assert_not_called()
