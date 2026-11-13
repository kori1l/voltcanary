"""Deterministic rejected-candidate provider for isolated assistant trace acceptance.

Run only inside the local Compose network; do not configure real user accounts
against this fixture. It performs no domain operation and never logs requests.
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        chunks = []
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            while True:
                size = int(self.rfile.readline().split(b";", 1)[0].strip(), 16)
                if not size:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
        else:
            chunks.append(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        request = json.loads(b"".join(chunks))
        candidate = {"version": "assistant-v2", "kind": "planner.response", "decision": "ASK",
            "explanation": "Select the intended resource.", "targetStepId": None, "supersedesStepIds": [],
            "question": {"text": "Which resource should this request use?", "missingFields": ["target"], "choices": []},
            "steps": [], "proposedGoals": [], "supersedesGoalIds": []}
        if request.get("model") != "history-fixture":
            candidate.update(dataRefs=["invented-ref"], apiKey="trace-fixture-secret-must-not-persist")
        response = {"id": "trace-fixture", "object": "chat.completion", "created": 1, "model": "fixture", "choices": [{"index": 0, "message": {"role": "assistant", "content": json.dumps(candidate),
                                             "reasoning_content": "private-fixture-reasoning-must-not-persist"}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}}
        payload = json.dumps(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8127), Handler).serve_forever()
