"""Opt-in local observer for a disposable OpenAI test provider, never a general proxy.

The original provider base URL is restored in finally. Keys/bodies are forwarded
in memory only. Only allowlisted call counts, usage and fault metadata are retained.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import re
from threading import Lock, Thread
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


class ProviderObserver:
    def __init__(self, client, provider, port=18779):
        self.api, self.provider, self.port = client, provider, port
        self.original = provider["baseUrl"].rstrip("/")
        self.records, self.faults, self.lock = [], {}, Lock()
        self.server = None

    def arm(self, marker):
        with self.lock:
            self.faults[marker] = True

    def records_for(self, marker, start=0):
        with self.lock:
            return [dict(x) for x in self.records[start:] if x.get("marker") == marker]

    def __enter__(self):
        if self.provider["providerType"] != "OPENAI" or urlsplit(self.original).scheme != "https":
            raise ValueError("Observer requires a disposable OPENAI provider with an HTTPS upstream")
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                if self.path not in ("/v1/chat/completions", "/chat/completions"):
                    with owner.lock:
                        owner.records.append({"marker": None, "status": 404,
                                              "rejectedPath": self.path[:100]})
                    self.send_error(404)
                    return
                try:
                    raw = read_body(self.rfile, self.headers)
                except ValueError:
                    self.send_error(413)
                    return
                body = json.loads(raw)
                marker_match = re.search(r"EVAL_[a-f0-9]+_[a-z0-9_]+_t\d+", raw.decode())
                marker = marker_match.group() if marker_match else None
                record = {"marker": marker, "model": body.get("model"), "faultInjected": False,
                          "usage": None, "status": None}
                try:
                    request = Request(owner.original + "/chat/completions", raw, {
                        "Authorization": self.headers.get("Authorization", ""),
                        "Content-Type": "application/json"}, method="POST")
                    with urlopen(request, timeout=600) as response:
                        output = response.read()
                        record["status"] = response.status
                    decoded = json.loads(output)
                    usage = decoded.get("usage", {})
                    record["usage"] = {"input_tokens": usage.get("prompt_tokens"),
                                       "output_tokens": usage.get("completion_tokens"),
                                       "total_tokens": usage.get("total_tokens")}
                    with owner.lock:
                        inject = owner.faults.get(marker, False)
                    if inject and inject_preparation_fault(decoded):
                        record["faultInjected"] = True
                        with owner.lock:
                            owner.faults[marker] = False
                        output = json.dumps(decoded).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(output)))
                    self.end_headers()
                    self.wfile.write(output)
                except HTTPError as error:
                    record["status"] = error.code
                    self.send_error(error.code)
                except (URLError, TimeoutError, ConnectionError, ValueError):
                    record["status"] = 502
                    self.send_error(502)
                finally:
                    with owner.lock:
                        owner.records.append(record)

        # Docker reaches this test-only host listener through host.docker.internal.
        self.server = ThreadingHTTPServer(("0.0.0.0", self.port), Handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.restore_required = True
        try:
            self.api.call("PUT", "/ai-agent/model-providers/" + self.provider["id"],
                          {"baseUrl": f"http://host.docker.internal:{self.port}/v1"})
            current = self.api.call("GET", "/ai-agent/model-providers/" + self.provider["id"])
            if current["baseUrl"] != f"http://host.docker.internal:{self.port}/v1":
                raise ValueError("Test provider route did not persist")
        except Exception:
            self.__exit__(None, None, None)
            raise
        return self

    def __exit__(self, *_):
        try:
            if getattr(self, "restore_required", False):
                self.api.call("PUT", "/ai-agent/model-providers/" + self.provider["id"],
                              {"baseUrl": self.original})
        finally:
            if self.server:
                self.server.shutdown()
                self.server.server_close()


def inject_preparation_fault(response):
    """One labelled fault after a real model reply; normal prepare rejects the value."""
    for choice in response.get("choices", []):
        message = choice.get("message", {})
        try:
            plan = json.loads(message["content"])
        except (ValueError, KeyError):
            continue
        if not isinstance(plan, dict):
            continue
        candidate = plan.get("proposal", plan)
        if not isinstance(candidate, dict):
            continue
        payloads = [action.get("parameters", {}) for action in candidate.get("actions", [])]
        if candidate.get("kind") == "step.response" and candidate.get("outcome") == "PREPARED":
            payloads.append(candidate.get("command", {}).get("payload", {}))
        for parameters in payloads:
            if parameters.get("protocol") == "dashboard-operations-v1":
                operations = parameters.get("operations", [])
                target = next((op.get("id") for op in operations if op.get("op") == "show_map"), None)
                if target:
                    # Same labelled boolean/string mismatch at the new protocol boundary.
                    used = {operation.get("id") for operation in operations}
                    identity = "eval_fault"
                    suffix = 0
                    while identity in used:
                        suffix += 1
                        identity = "eval_fault_" + str(suffix)
                    operations.append({"op": "set_presentation", "id": identity,
                                       "targets": ["@" + target],
                                       "settings": [{"key": "showMapNotices", "value": "false"}]})
                    message["content"] = json.dumps(plan, ensure_ascii=False)
                    return True
            widgets = parameters.get("widgets", [])
            for widget in widgets:
                if widget.get("type") == "vehicle-map":
                    widget.setdefault("settings", {})["showMapNotices"] = "false"
                    message["content"] = json.dumps(plan, ensure_ascii=False)
                    return True
    return False


def read_body(stream, headers, limit=2_000_000):
    if headers.get("Transfer-Encoding", "").lower() == "chunked":
        chunks, size = [], 0
        while True:
            line = stream.readline(100)
            count = int(line.split(b";", 1)[0].strip(), 16)
            if count == 0:
                # Consume bounded trailer lines, without storing their content.
                for _ in range(30):
                    if stream.readline(4096) in (b"\r\n", b"\n", b""):
                        break
                return b"".join(chunks)
            size += count
            if size > limit:
                raise ValueError("Request too large")
            chunks.append(stream.read(count))
            if stream.read(2) != b"\r\n":
                raise ValueError("Malformed chunk")
    length = int(headers.get("Content-Length", 0))
    if not 0 < length <= limit:
        raise ValueError("Request size required")
    return stream.read(length)
