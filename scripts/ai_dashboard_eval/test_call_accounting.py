import unittest

from .call_accounting import first_pass, from_trace, quality_metrics


class CallAccountingTest(unittest.TestCase):
    def test_denied_reservation_consumes_budget_without_provider_dispatch(self):
        details = {"callId": "denied", "reservedCostMicros": 100}
        reserved = {"stage": "BUDGET", "status": "RESERVED", "details": details}
        denied = {"stage": "BUDGET", "status": "FAILED", "code": "DISPATCH_ACCESS_DENIED", "details": details}
        self.assertFalse(from_trace(self.events(["PRIMARY"]) + [reserved])["complete"])
        accounting = from_trace([reserved, denied])
        self.assertTrue(accounting["complete"])
        self.assertEqual(0, accounting["modelCalls"])
        self.assertEqual([details], accounting["deniedReservations"])

    def test_embedding_calls_count_against_budget_but_cannot_prove_generation(self):
        retrieval = self.events(["CATALOGUE_INDEX", "CATALOGUE_QUERY"])
        accounting = from_trace(retrieval)
        self.assertTrue(accounting["complete"])
        self.assertEqual(2, accounting["modelCalls"])
        self.assertEqual(0, accounting["repairCalls"])
        self.assertIsNone(first_pass(True, accounting))
        combined = from_trace(self.events(["CATALOGUE_INDEX", "CATALOGUE_QUERY", "PRIMARY"]))
        self.assertEqual(3, combined["modelCalls"])
        self.assertTrue(first_pass(True, combined))

    def events(self, purposes):
        events = []
        for index, purpose in enumerate(purposes):
            details = {"callId": str(index), "purpose": purpose, "phase": "STEP"}
            events.append({"stage": "MODEL_CALL", "status": "STARTED", "details": details})
            events.append({"stage": "MODEL_CALL", "status": "SUCCEEDED", "details": {**details, "usage": {"inputTokens": 7}}})
        return events

    def test_required_planner_step_and_context_calls_are_first_pass(self):
        accounting = from_trace(self.events(["PRIMARY", "PRIMARY", "CONTEXT_ACQUISITION", "CONTEXT_ACQUISITION"]))
        self.assertEqual(4, accounting["modelCalls"])
        self.assertEqual(0, accounting["repairCalls"])
        self.assertTrue(first_pass(True, accounting))
        self.assertFalse(first_pass(False, accounting))

    def test_consultation_is_budgeted_support_for_the_same_first_pass(self):
        accounting = from_trace(self.events(["PRIMARY", "PRIMARY", "CONSULTATION"]))
        self.assertTrue(accounting["complete"])
        self.assertEqual(3, accounting["modelCalls"])
        self.assertEqual(0, accounting["repairCalls"])
        self.assertEqual(0, accounting["assessmentCalls"])
        self.assertTrue(first_pass(True, accounting))
        self.assertIsNone(first_pass(True, from_trace(self.events(["CONSULTATION"]))))

    def test_retry_never_becomes_an_independent_trial_or_first_pass_success(self):
        accounting = from_trace(self.events(["PRIMARY", "FORMAT_CORRECTION", "DOMAIN_RECOVERY"]))
        self.assertFalse(first_pass(True, accounting))
        turns = [{"passed": True, "firstTryPassed": False, "repairCalls": 2},
                 {"passed": False, "firstTryPassed": False, "category": "NOT_REACHED"},
                 {"passed": True, "firstTryPassed": True}]
        report = quality_metrics(turns)
        self.assertEqual(3, report["firstPassTrials"])
        self.assertEqual(1, report["firstPassSuccesses"])
        self.assertEqual(2, report["initialUnsuccessfulTrials"])
        self.assertEqual(0.5, report["recoveryRate"])
        self.assertEqual(2, report["finalSuccesses"])

    def test_old_untyped_calls_are_unknown_not_zero_repairs_or_first_pass_pass(self):
        accounting = from_trace(self.events([None]))
        self.assertIsNone(accounting["repairCalls"])
        self.assertIsNone(first_pass(True, accounting))
        self.assertFalse(first_pass(False, accounting))

    def test_duplicate_start_is_counted_once_and_uncertain_usage_stays_unknown(self):
        events = self.events(["PRIMARY"])
        accounting = from_trace([events[0], events[0]])
        self.assertEqual(1, accounting["modelCalls"])
        self.assertEqual("UNKNOWN", accounting["calls"][0]["status"])
        self.assertIsNone(accounting["calls"][0]["usage"])

    def test_unrelated_bounded_context_trace_does_not_erase_complete_call_accounting(self):
        events = self.events(["PRIMARY", "PRIMARY"])
        events.append({"stage": "CONTEXT", "truncated": True})
        self.assertTrue(from_trace(events)["complete"])
        events[0]["truncated"] = True
        self.assertFalse(from_trace(events)["complete"])

    def test_known_pre_model_rejection_is_zero_calls_but_never_a_generation_success(self):
        events = [{"stage": "PREPARATION", "status": "FAILED", "code": "DASHBOARD_BASE_REQUIRES_EDIT_SKILL"}]
        accounting = from_trace(events)
        self.assertTrue(accounting["complete"])
        self.assertEqual(0, accounting["modelCalls"])
        self.assertEqual(0, accounting["repairCalls"])
        self.assertFalse(first_pass(False, accounting))
        self.assertIsNone(first_pass(True, accounting))
        for stage in ("DIAGNOSTIC_READ", "MODEL_RUN", "PROVIDER_USAGE"):
            self.assertFalse(from_trace(events + [{"stage": stage, "status": "UNKNOWN"}])["complete"])
        self.assertFalse(from_trace(events + [{"stage": "BUDGET", "status": "RESERVED"}])["complete"])

    def test_assessment_has_its_own_purpose_and_is_not_a_repair(self):
        accounting = from_trace(self.events(["PRIMARY", "OUTCOME_ASSESSMENT", "DOMAIN_RECOVERY", "OUTCOME_ASSESSMENT"]))
        self.assertTrue(accounting["complete"])
        self.assertEqual(2, accounting["assessmentCalls"])
        self.assertEqual(1, accounting["repairCalls"])
        self.assertFalse(first_pass(True, accounting))
