"""Explicit isolated execution/renderer job, separate from planning evaluation."""
import argparse
import json
from pathlib import Path

from ai_dashboard_eval.api import ApiError, ROOT, authenticate, write_json
from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.execution import cleanup_handoff, execute_dashboard_lifecycle, execute_fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    parser.add_argument("--account", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--confirm-fixture-execution", action="store_true", required=True)
    parser.add_argument("--renderer-reference", action="store_true")
    parser.add_argument("--keep-for-browser", action="store_true")
    parser.add_argument("--cleanup-handoff", type=Path)
    parser.add_argument("--dashboard-lifecycle", action="store_true", help="Verify confirmed create and same-ID update, then clean up")
    args = parser.parse_args()
    if ROOT / "artifacts" not in args.output.resolve().parents:
        parser.error("Output must be inside artifacts/")
    if args.dashboard_lifecycle and (args.renderer_reference or args.keep_for_browser or args.cleanup_handoff):
        parser.error("--dashboard-lifecycle requires an isolated run with automatic cleanup")
    if args.dashboard_lifecycle and args.output.exists():
        parser.error("Use a fresh output path for a lifecycle run")
    account = json.loads(args.account.read_text(encoding="utf-8-sig"))
    try:
        client, _ = authenticate(args.gateway, account)
        with AccountLease(account["accountId"], "EXECUTION_OR_RENDERER"):
            if args.cleanup_handoff:
                report = cleanup_handoff(client, json.loads(args.cleanup_handoff.read_text(encoding="utf-8")))
            elif args.dashboard_lifecycle:
                report = execute_dashboard_lifecycle(client, account, args.output.parent / (args.output.stem + "-trace"))
            else:
                report = execute_fixture(client, account, args.renderer_reference, args.keep_for_browser)
    except ApiError as error:
        report = {"status": "BLOCKED", "errorCode": error.code, "httpStatus": error.status}
    write_json(args.output, report)
    print(json.dumps({key: report.get(key) for key in ("job", "status", "dashboardId", "cleanup")}))
    return 0 if report.get("status") == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
