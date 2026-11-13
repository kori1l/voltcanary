"""Offline harness tests challenge outcomes and failure reporting, not model prose."""
from copy import deepcopy
import io
import json
from pathlib import Path
import unittest
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

from .api import ApiError, Client, ROOT, authenticate
from urllib.error import HTTPError
from .account_lock import AccountLease
from .grading import classify, definition, grade
from .observer import inject_preparation_fault, read_body
from .runner import Runner, expand, summarize, validate_corpus, validate_expectation


def doc():
    widgets = [
        {"id": "map", "type": "vehicle-map", "title": "Карта", "query": {"sourceId": "source"},
         "binding": {"deviceIds": ["owned"]}, "presentation": {"showTitle": True}},
        {"id": "speed", "type": "vehicle-value", "title": "Скорость", "query": {"sourceId": "source"},
         "binding": {"deviceIds": ["owned"]}, "presentation": {"vehicleField": "speedKmh"}},
        {"id": "doors", "type": "vehicle-value", "title": "Двери", "query": {"sourceId": "source"},
         "binding": {"deviceIds": ["owned"]}, "presentation": {"vehicleField": "transport.doorsOpen"}}]
    positions = [{"widgetId": w["id"], "h": 1, "w": 1, "x": 0, "y": i} for i, w in enumerate(widgets)]
    return {"name": "Мой трамвай", "defaultContext": {"scope": {"deviceIds": ["owned"]}},
            "widgets": widgets, "layouts": {bp: deepcopy(positions) for bp in ("desktop", "tablet", "mobile")}}


def plan(value=None):
    return {"planningStatus": "READY", "actions": [{"tool": "CREATE_DASHBOARD",
            "parameters": {"definition": value or doc()}}]}


def failed(checks):
    return {c["id"] for c in checks if not c["passed"]}


class OutcomeTests(unittest.TestCase):
    def test_history_period_is_scoped_to_requested_widget_and_wrong_or_missing_period_fails(self):
        value = doc()
        value["defaultContext"]["time"] = {"preset": "LAST_24_HOURS"}
        value["widgets"] = [{"id": "history", "type": "time-series", "query": {"metric": "speedKmh", "time": {"preset": "LAST_6_HOURS"}}}]
        expected = {"historyPeriods": {"history:speed": "LAST_6_HOURS"}}
        self.assertNotIn("history_period:history:speed", failed(grade(plan(value), expected)))
        value["widgets"][0]["query"]["time"]["preset"] = "LAST_1_HOUR"
        self.assertIn("history_period:history:speed", failed(grade(plan(value), expected)))
        value["widgets"][0]["query"].pop("time")
        self.assertIn("history_period:history:speed", failed(grade(plan(value), expected)))
        value["defaultContext"]["time"]["preset"] = "LAST_6_HOURS"
        self.assertNotIn("history_period:history:speed", failed(grade(plan(value), expected)))

    def test_delegated_design_has_no_fixed_widget_bundle_but_explicit_content_is_exact(self):
        candidate = plan()
        self.assertFalse(failed(grade(candidate, {})))
        self.assertIn("only_explicit_roles", failed(grade(candidate, {"onlyRoles": ["current:speed"]})))
        candidate["actions"][0]["parameters"]["definition"]["widgets"] = [doc()["widgets"][1]]
        self.assertNotIn("only_explicit_roles", failed(grade(candidate, {"onlyRoles": ["current:speed"]})))
    def test_wrong_name_fails_despite_success_prose(self):
        candidate = plan()
        candidate["explanation"] = "Всё готово, название сохранено"
        self.assertIn("exact_name", failed(grade(candidate, {"name": "Другое имя"})))

    def test_binding_corruption_fails(self):
        old = doc()
        new = deepcopy(old)
        new["widgets"][0]["binding"]["deviceIds"] = ["foreign"]
        errors = failed(grade(plan(new), {"preserve": {}}, old, device_ids=["owned"]))
        self.assertIn("authorized_fixture_bindings", errors)
        self.assertIn("untargeted_widget_fields", errors)

    def test_requested_title_only_passes(self):
        old = doc()
        new = deepcopy(old)
        new["widgets"][1]["title"] = "Сейчас"
        expected = {"titles": {"current:speed": "Сейчас"}, "preserve": {"current:speed": ["/title"]}}
        self.assertFalse(failed(grade(plan(new), expected, old)))

    def test_hidden_extra_edit_fails(self):
        old = doc()
        new = deepcopy(old)
        new["widgets"][1]["title"] = "Сейчас"
        new["widgets"][0]["query"]["sourceId"] = "other"
        self.assertIn("untargeted_widget_fields", failed(grade(plan(new),
            {"preserve": {"current:speed": ["/title"]}}, old)))

    def test_noop_does_not_satisfy_hide_request(self):
        self.assertIn("presentation:map:showTitle", failed(grade(plan(),
            {"presentation": {"map": {"showTitle": False}}}, doc())))

    def test_already_satisfied_property_can_pass(self):
        value = doc()
        value["widgets"][0]["presentation"]["showTitle"] = False
        self.assertFalse(failed(grade(plan(value),
            {"presentation": {"map": {"showTitle": False}}, "unchanged": True}, value)))

    def test_wrong_type_cannot_masquerade_as_current_reading(self):
        value = doc()
        value["widgets"][1]["type"] = "context-note"
        self.assertIn("role:current:speed", failed(grade(plan(value), {"roles": ["current:speed"]})))

    def test_duplicate_role_fails(self):
        value = doc()
        value["widgets"].append({**deepcopy(value["widgets"][1]), "id": "duplicate"})
        self.assertIn("role:current:speed", failed(grade(plan(value), {"roles": ["current:speed"]})))

    def test_title_only_repacking_fails(self):
        old, new = doc(), doc()
        new["layouts"]["desktop"][0]["y"] = 4
        self.assertIn("unchanged_geometry", failed(grade(plan(new), {"preserve": {}}, old)))

    def test_allowed_reflow_preserves_relative_order(self):
        old, new = doc(), doc()
        for positions in new["layouts"].values():
            for p in positions:
                p["y"] = {"speed": 0, "map": 1, "doors": 2}[p["widgetId"]]
        expected = {"top": ["current:speed"], "preserve": {}, "allowReflow": True}
        self.assertFalse(failed(grade(plan(new), expected, old)))
        new["layouts"]["mobile"][0]["y"] = 9
        self.assertIn("untouched_order:mobile", failed(grade(plan(new), expected, old)))

    def test_restore_must_be_exact(self):
        value = doc()
        value["name"] = "Приблизительно восстановлено"
        self.assertIn("exact_restored_definition", failed(grade(plan(value), {}, exact=doc())))

    def test_numeric_zero_and_false_not_compared_as_missing(self):
        value = doc()
        value["widgets"][1]["presentation"]["showDetails"] = False
        checks = grade(plan(value), {"presentation": {"current:speed": {"showDetails": False}}})
        self.assertFalse(failed(checks))


class ReportingTests(unittest.TestCase):
    def test_account_lease_blocks_other_job_and_releases_after_failure(self):
        account = "00000000-0000-0000-0000-000000000001"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "injected"):
                with AccountLease(account, "planning", directory):
                    with self.assertRaisesRegex(ApiError, "EVALUATION_ACCOUNT_BUSY"):
                        with AccountLease(account, "execution", directory):
                            self.fail("Account inventory was not isolated")
                    raise RuntimeError("injected")
            with AccountLease(account, "execution", directory):
                self.assertTrue((Path(directory) / (account + ".lock")).exists())

    def test_skipped_provider_cannot_pass_live_trial(self):
        candidate = {**plan(), "planId": "p", "modelId": "model", "modelConfigId": "cfg", "fallbackUsed": False}
        fake_api = SimpleNamespace(call=lambda *_: candidate)

        class FakeFixture:
            def __init__(self, *_):
                self.devices, self.archived, self.cleanup_failures = ["owned"], [], []
            def __enter__(self):
                return self
            def __exit__(self, *_):
                pass
            def metadata(self):
                return {"synthetic": True}
            def refresh(self):
                pass

        observer = SimpleNamespace(records=[], records_for=lambda *_: [])
        case = {"id": "no-model", "critical": False, "split": "development", "profile": "normal",
                "turns": [{"id": "one", "action": "message", "message": "fixture", "expect": {"name": "Мой трамвай"}}]}
        with tempfile.TemporaryDirectory() as output:
            with patch("scripts.ai_dashboard_eval.runner.VehicleFixture", FakeFixture):
                runner = Runner(fake_api, {"modelId": "model", "modelConfigId": "cfg", "providerId": "provider"}, output, observer)
                result = runner.run_case(case, 1)
        self.assertFalse(result["passed"])
        self.assertIn("provider_invocation_observed", failed(result["turns"][0]["checks"]))

    def test_assertions_are_not_trials(self):
        checks = [{"id": str(i), "passed": True} for i in range(21)]
        result = {"caseId": "one", "passed": True, "turns": [
            {"checks": checks, "passed": True, "category": "MODEL_RESULT", "modelCalls": 1}]}
        report = summarize([result], 1)
        self.assertEqual((report["trialCount"], report["assertionCount"]), (1, 21))
        self.assertEqual(report["finalReleaseGate"], "NOT_EVALUATED_BASELINE_ONLY")

    def test_repeated_trial_failure_is_not_hidden(self):
        good = {"caseId": "one", "passed": True, "turns": []}
        bad = {"caseId": "one", "passed": False, "turns": []}
        report = summarize([good, bad], 2)
        self.assertEqual(report["passedTrials"], 1)
        self.assertEqual(report["allTrialsPassedConversations"], 0)
        self.assertEqual(report["qualityStatus"], "FAILED")

    def test_unobserved_calls_are_unknown_not_zero(self):
        result = {"caseId": "one", "passed": False, "turns": [
            {"checks": [], "passed": False, "category": "NOT_REACHED", "modelCalls": None}]}
        report = summarize([result], 1)
        self.assertIsNone(report["modelCalls"])
        self.assertEqual(report["unavailableTurns"], 1)

    def test_provider_failure_is_not_a_successful_question(self):
        self.assertEqual(classify({"safetyNotes": ["Provider request failed; no alternate model was selected."]}), "PROVIDER_FAILURE")
        self.assertEqual(classify(None, ApiError(503)), "AVAILABILITY_FAILURE")
        self.assertEqual(classify(None, ApiError(500)), "APPLICATION_FAILURE")
        self.assertEqual(classify(None, ApiError(502, "MODEL_OUTPUT_LIMIT")), "MODEL_CAPACITY_FAILURE")

    def test_auth_preflight_requires_credentials(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaisesRegex(ApiError, "AUTH_ENV_REQUIRED"):
                authenticate("http://127.0.0.1:1", {})

    def test_config_parity_prevents_the_old_token_limit_baseline_mistake(self):
        account = {"accountId": "owner", "providerId": "p", "modelConfigId": "m", "modelId": "nano",
                   "expectedModelSettings": {"maxOutputTokens": 32000}}
        responses = [{"id": "owner"}, {"models": [{"id": "m", "enabled": True, "modelId": "nano", "maxOutputTokens": 4096}]}]
        with patch.dict("os.environ", {"VOLT_EVAL_ACCESS_TOKEN": "fixture"}, clear=True):
            with patch("scripts.ai_dashboard_eval.api.Client.call", side_effect=responses):
                with self.assertRaisesRegex(ApiError, "MODEL_SETTINGS_MISMATCH"):
                    authenticate("http://unused", account)


class CorpusAndObserverTests(unittest.TestCase):
    def test_alternative_requires_visible_disclosure(self):
        candidate = plan(doc())
        expected = {"disclosureAny": ["destination"]}
        self.assertIn("alternative_is_disclosed", failed(grade(candidate, expected)))
        candidate["explanation"] = "Destination history is shown as a list."
        self.assertNotIn("alternative_is_disclosed", failed(grade(candidate, expected)))

    def test_lifecycle_and_foreign_access_are_separate_cases(self):
        cases = {c["id"]: c for c in self.corpus["cases"]}
        lifecycle = cases["revoked-device"]["turns"][-1]
        self.assertNotIn("expectedHttp", lifecycle)
        self.assertIn(404, lifecycle["allowedHttp"])
        foreign = cases["foreign-task-isolation"]["turns"][-1]
        self.assertEqual(foreign["action"], "foreign")
        self.assertEqual(foreign["expectedHttp"], [404])

    @classmethod
    def setUpClass(cls):
        path = ROOT / "backend/services/ai-agent-service/src/test/resources/evaluation/dashboard-conversation-ru-v1.json"
        cls.corpus = json.loads(path.read_text(encoding="utf-8"))

    def test_frozen_corpus_and_heldout_split(self):
        validate_corpus(self.corpus)
        self.assertGreaterEqual(sum(c["split"] == "held-out" for c in self.corpus["cases"]), 2)
        self.assertEqual(sum(c["critical"] for c in self.corpus["cases"]), 8)

    def test_corpus_rejects_unsafe_mutation_policy(self):
        value = deepcopy(self.corpus)
        value["cases"][0]["mutationPolicy"] = "EXECUTE_ANYTHING"
        with self.assertRaises(ValueError):
            validate_corpus(value)

    def test_unknown_outcome_predicate_cannot_silently_pass(self):
        with self.assertRaises(ValueError):
            validate_expectation({"preserveBindingsTypo": True})

    def test_disclosure_predicate_cannot_be_a_bare_string(self):
        with self.assertRaises(ValueError):
            validate_expectation({"disclosureAny": "text"})

    def test_http_success_cannot_be_an_expected_rejection(self):
        value = deepcopy(self.corpus)
        value["cases"][0]["turns"][0]["expectedHttp"] = [200]
        with self.assertRaises(ValueError):
            validate_corpus(value)

    def test_history_cannot_target_its_own_unreached_turn(self):
        value = deepcopy(self.corpus)
        value["cases"][0]["turns"][0] = {"id": "self", "action": "select", "target": "self"}
        with self.assertRaises(ValueError):
            validate_corpus(value)

    def test_substitution_retains_original_russian(self):
        self.assertEqual(expand("Назови {{name}}", {"name": "Мой трамвай"}), "Назови Мой трамвай")

    def test_chunked_spring_http_body_is_supported(self):
        stream = io.BytesIO(b'4\r\n{"a"\r\n3\r\n:1}\r\n0\r\n\r\n')
        self.assertEqual(read_body(stream, {"Transfer-Encoding": "chunked"}), b'{"a":1}')

    def test_oversized_proxy_body_rejected(self):
        with self.assertRaises(ValueError):
            read_body(io.BytesIO(), {"Content-Length": "9999999"})

    def test_preparation_fault_is_specific_and_single_value(self):
        draft = {"actions": [{"parameters": {"widgets": [{"type": "vehicle-map", "key": "map"}]}}]}
        response = {"choices": [{"message": {"content": json.dumps(draft)}}]}
        self.assertTrue(inject_preparation_fault(response))
        mutated = json.loads(response["choices"][0]["message"]["content"])
        self.assertEqual(mutated["actions"][0]["parameters"]["widgets"][0]["settings"], {"showMapNotices": "false"})
        self.assertEqual(mutated["actions"][0]["parameters"]["widgets"][0]["key"], "map")

    def test_semantic_fault_keeps_the_same_labelled_boolean_string_mismatch(self):
        draft = {"actions": [{"parameters": {"protocol": "dashboard-operations-v1", "operations": [
            {"op": "show_map", "id": "map", "dataRef": "owned-choice"}]}}]}
        response = {"choices": [{"message": {"content": json.dumps(draft)}}]}
        self.assertTrue(inject_preparation_fault(response))
        operations = json.loads(response["choices"][0]["message"]["content"])["actions"][0]["parameters"]["operations"]
        self.assertEqual(operations[0], draft["actions"][0]["parameters"]["operations"][0])
        self.assertEqual(operations[1]["targets"], ["@map"])
        self.assertEqual(operations[1]["settings"], [{"key": "showMapNotices", "value": "false"}])

    def test_fault_injection_supports_primary_step_envelopes_without_duplicate_ids(self):
        draft = {"version": "assistant-v2", "kind": "step.response", "outcome": "PREPARED", "command": {"payload": {
            "protocol": "dashboard-operations-v1", "operations": [{"op": "show_map", "id": "eval_fault", "dataRef": "owned"}]}}}
        response = {"choices": [{"message": {"content": json.dumps(draft)}}]}
        self.assertTrue(inject_preparation_fault(response))
        operations = json.loads(response["choices"][0]["message"]["content"])["command"]["payload"]["operations"]
        self.assertNotEqual(operations[0]["id"], operations[1]["id"])
        self.assertEqual(operations[1]["targets"], ["@eval_fault"])
        self.assertEqual(operations[1]["settings"][0]["value"], "false")

    def test_fault_observer_does_not_crash_on_non_object_model_output(self):
        response = {"choices": [{"message": {"content": "[]"}}]}
        self.assertFalse(inject_preparation_fault(response))


class NormalSessionRefreshTests(unittest.TestCase):
    def test_near_expiry_is_renewed_before_the_first_model_submission(self):
        import base64
        import time
        def jwt(seconds):
            data = base64.urlsafe_b64encode(json.dumps({"exp": time.time() + seconds}).encode()).decode().rstrip("=")
            return "header." + data + ".signature"
        class Response:
            def __init__(self, payload): self.payload = payload
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def read(self): return json.dumps(self.payload).encode()
        renewed = jwt(900)
        with patch("scripts.ai_dashboard_eval.api.urlopen", side_effect=[Response({"accessToken": renewed, "refreshToken": "rotated"}), Response({"id": "result"})]) as request:
            client = Client("http://local", jwt(120), refresh_token="refresh")
            self.assertEqual(client.call("POST", "/ai-agent/conversations/id/messages", {"requestId": "same"}), {"id": "result"})
            self.assertEqual(request.call_args_list[0].args[0].full_url, "http://local/auth/refresh")
            self.assertEqual(request.call_args_list[1].args[0].get_header("Authorization"), "Bearer " + renewed)

    def test_expired_session_refreshes_in_memory_once_and_retries_with_the_same_body(self):
        class Response:
            def __init__(self, payload):
                self.payload = payload
            def __enter__(self):
                return self
            def __exit__(self, *_):
                pass
            def read(self):
                return json.dumps(self.payload).encode()
        expired = HTTPError("http://local/devices", 401, "expired", {}, io.BytesIO(b'{}'))
        replies = [expired, Response({"accessToken": "renewed", "refreshToken": "rotated"}), Response({"id": "owned"})]
        with patch("scripts.ai_dashboard_eval.api.urlopen", side_effect=replies) as request:
            client = Client("http://local", "expired", refresh_token="initial-refresh")
            self.assertEqual(client.call("POST", "/devices", {"name": "fixture"}), {"id": "owned"})
            self.assertEqual(client.refresh_token, "rotated")
            first, refresh, retry = [call.args[0] for call in request.call_args_list]
            self.assertEqual(first.data, retry.data)
            self.assertEqual(refresh.full_url, "http://local/auth/refresh")
            self.assertEqual(retry.get_header("Authorization"), "Bearer renewed")

    def test_foreign_explicit_token_never_refreshes_using_the_primary_identity(self):
        expired = HTTPError("http://local/device", 401, "expired", {}, io.BytesIO(b'{}'))
        with patch("scripts.ai_dashboard_eval.api.urlopen", side_effect=expired) as request:
            client = Client("http://local", "primary", refresh_token="primary-refresh")
            with self.assertRaises(ApiError):
                client.call("GET", "/device", token="foreign")
            self.assertEqual(request.call_count, 1)


if __name__ == "__main__":
    unittest.main()
