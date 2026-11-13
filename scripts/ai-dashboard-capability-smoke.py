"""Authenticated measured-fixture capability checks. Does not invoke a provider or execute dashboards."""
import argparse
import json
from pathlib import Path

from ai_dashboard_eval.account_lock import AccountLease
from ai_dashboard_eval.api import ROOT, authenticate, write_json
from ai_dashboard_eval.capability_smoke import run_capability_smoke


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if ROOT / "artifacts" not in args.output.resolve().parents:
        parser.error("Output must be inside artifacts/")
    account = json.loads(args.account.read_text(encoding="utf-8-sig"))
    client, _ = authenticate("http://127.0.0.1:8080", {k: v for k, v in account.items() if k != "expectedModelSettings"})
    with AccountLease(account["accountId"], "CAPABILITY_CONTRACT"):
        report = run_capability_smoke(client)
        write_json(args.output, report)
    print(json.dumps({"status": report["status"], "checks": len(report["checks"])}))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
