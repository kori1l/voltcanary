"""Submit an operator-qualified closed source interval with crash-safe retries.

The input evidence is a complete producer prefix, not a sample of readings. This
tool does not infer completeness from journal continuity or polling frequency.
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path


class RefuseRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ValueError("Owner request redirect refused")


OPENER = urllib.request.build_opener(RefuseRedirect)


def instant(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z", value):
        raise ValueError("Canonical UTC timestamp required")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.astimezone(timezone.utc)


def uuid_text(value):
    parsed = uuid.UUID(value)
    if str(parsed) != value:
        raise ValueError("Canonical UUID required")
    return value


def read_manifest(path):
    value = json.loads(path.read_text(encoding="utf-8"))
    expected = {"profile", "proofId", "bindingId", "accountId", "deviceId", "schemaRevision",
                "snapshotId", "producerEpoch", "throughSequence", "from", "until", "evidenceFile",
                "evidenceSha256"}
    if set(value) != expected or value["profile"] != "source-coverage-statement-1":
        raise ValueError("Closed source statement required")
    for name in ("proofId", "bindingId", "accountId", "deviceId", "snapshotId", "producerEpoch"):
        uuid_text(value[name])
    sequence = value["throughSequence"]
    if not isinstance(sequence, str) or not sequence.isdecimal() or str(int(sequence)) != sequence or int(sequence) < 1:
        raise ValueError("Invalid producer sequence")
    if not isinstance(value["schemaRevision"], int) or isinstance(value["schemaRevision"], bool) or value["schemaRevision"] < 1:
        raise ValueError("Invalid schema revision")
    if not isinstance(value["evidenceSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", value["evidenceSha256"]):
        raise ValueError("Invalid source evidence digest")
    start, end = instant(value["from"]), instant(value["until"])
    if not start < end <= datetime.now(timezone.utc) or end - start > timedelta(hours=24):
        raise ValueError("Invalid closed interval")
    evidence = (path.parent / value["evidenceFile"]).resolve()
    if not evidence.is_file() or evidence.stat().st_size > 10 * 1024 * 1024:
        raise ValueError("Bounded evidence file required")
    raw = evidence.read_bytes()
    if hashlib.sha256(raw).hexdigest() != value["evidenceSha256"]:
        raise ValueError("Source evidence changed")
    lines = raw.splitlines()
    if len(lines) != int(sequence) or len(lines) > 100000:
        raise ValueError("Complete producer prefix required")
    for index, line in enumerate(lines, 1):
        row = json.loads(line)
        if set(row) != {"sequence", "eventTime"} or row["sequence"] != str(index):
            raise ValueError("Producer prefix has a gap or extra field")
        instant(row["eventTime"])
    return value, hashlib.sha256(path.read_bytes()).hexdigest()


def request(base, path, token_header, token, body=None):
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password or parsed.path not in ("", "/"):
        raise ValueError("Service origin required")
    url = base.rstrip("/") + path
    headers = {token_header: token}
    if body is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    call = urllib.request.Request(url, data=body, headers=headers, method="POST" if body is not None else "GET")
    try:
        with OPENER.open(call, timeout=10) as response:
            if response.status != 200:
                raise ValueError("Owner response unavailable")
            raw = response.read(1_048_577)
            if len(raw) > 1_048_576:
                raise ValueError("Owner response too large")
            return json.loads(raw)
    except urllib.error.HTTPError as error:
        if error.code in (401, 403, 409):
            raise ValueError("Owner authorization or statement conflict") from error
        raise RuntimeError("Owner temporarily unavailable") from error


def ledger(connection, proof_id, digest):
    connection.execute("CREATE TABLE IF NOT EXISTS proof (id TEXT PRIMARY KEY, digest TEXT NOT NULL, generation TEXT, status TEXT NOT NULL)")
    connection.execute("INSERT OR IGNORE INTO proof(id,digest,status) VALUES(?,?,'PENDING')", (proof_id, digest))
    connection.commit()
    row = connection.execute("SELECT digest,generation,status FROM proof WHERE id=?", (proof_id,)).fetchone()
    if row[0] != digest:
        raise ValueError("Proof ID reused for different statement")
    return row


def advance(manifest, digest, state, device_url, telemetry_url, bearer, attestor):
    with sqlite3.connect(state) as connection:
        saved_digest, generation, status = ledger(connection, manifest["proofId"], digest)
        binding = manifest["bindingId"]
        source = request(device_url, f"/data/source-bindings/{binding}/capture?schemaRevision={manifest['schemaRevision']}",
                         "Authorization", "Bearer " + bearer)
        owner, capture = source["source"], source["capture"]
        pin = capture["pin"]
        if (owner["id"] != binding or owner["accountId"] != manifest["accountId"] or owner["deviceId"] != manifest["deviceId"] \
                or pin["snapshotId"] != manifest["snapshotId"] or pin["bindingId"] != binding \
                or pin["accountId"] != manifest["accountId"] or pin["deviceId"] != manifest["deviceId"] \
                or pin["schemaRevision"] != manifest["schemaRevision"] or not owner["sourceActive"]):
            raise ValueError("Source owner pin changed")
        if not capture["journalReady"] or capture["inputGeneration"] is None:
            return "UNKNOWN"
        current = uuid_text(capture["inputGeneration"])
        if generation is not None and generation != current:
            raise ValueError("Input generation changed")
        if generation is None:
            connection.execute("UPDATE proof SET generation=? WHERE id=?", (current, manifest["proofId"]))
            connection.commit()
        proof_path = f"/data/source-bindings/{binding}/coverage/{manifest['proofId']}"
        observed = request(device_url, proof_path, "Authorization", "Bearer " + bearer, pin)
        if observed["coverage"] == "UNKNOWN" and observed["reason"] == "PROOF_UNAVAILABLE":
            statement = {"pin": pin, "proofId": manifest["proofId"], "inputGeneration": current,
                         "producerEpoch": manifest["producerEpoch"], "throughSequence": manifest["throughSequence"],
                         "from": manifest["from"], "until": manifest["until"]}
            request(telemetry_url, "/internal/observations/source-coverage",
                    "X-Coverage-Attestor-Token", attestor, statement)
            observed = request(device_url, proof_path, "Authorization", "Bearer " + bearer, pin)
        if observed["coverage"] == "COVERED":
            evidence = observed["evidence"]
            if observed["proofId"] != manifest["proofId"] or observed["inputGeneration"] != current \
                    or observed["snapshotId"] != manifest["snapshotId"] or evidence["position"] != \
                    {"epoch": manifest["producerEpoch"], "sequence": manifest["throughSequence"]} \
                    or evidence["from"] != manifest["from"] or evidence["until"] != manifest["until"]:
                raise ValueError("Coverage owner returned a different proof")
            connection.execute("UPDATE proof SET status='COVERED' WHERE id=?", (manifest["proofId"],))
            connection.commit()
            return "COVERED"
        if observed["coverage"] != "UNKNOWN" or observed["reason"] not in (
                "PROOF_UNAVAILABLE", "SOURCE_GAP", "JOURNAL_UNAVAILABLE", "CONTRADICTORY_OBSERVATION"):
            raise ValueError("Coverage owner response invalid")
        connection.execute("UPDATE proof SET status='PENDING' WHERE id=?", (manifest["proofId"],))
        connection.commit()
        return "UNKNOWN"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--device-url", default="http://localhost:8082")
    parser.add_argument("--telemetry-url", default="http://localhost:8084")
    args = parser.parse_args()
    bearer = os.environ.get("VCL_OWNER_BEARER_TOKEN", "")
    attestor = os.environ.get("VCL_COVERAGE_ATTESTOR_TOKEN", "")
    if not bearer or not attestor:
        parser.error("VCL_OWNER_BEARER_TOKEN and VCL_COVERAGE_ATTESTOR_TOKEN are required")
    statement, digest = read_manifest(args.manifest)
    status = advance(statement, digest, args.state, args.device_url, args.telemetry_url, bearer, attestor)
    print(status)
    return 0 if status == "COVERED" else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError, sqlite3.Error) as failure:
        print(f"coverage attestation failed: {failure}", file=sys.stderr)
        sys.exit(1)
