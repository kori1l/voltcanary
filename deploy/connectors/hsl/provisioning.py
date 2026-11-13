"""Private owner-authorized HSL setup. Never expose ingestion credentials."""
import hmac
import json
import logging
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import RLock, Thread
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID


class SetupError(Exception):
    def __init__(self, status):
        self.status = status


class Provisioning:
    def __init__(self, path, device_url, telemetry_url, api=None):
        self.path = Path(path)
        self.device_url = device_url.rstrip("/")
        self.telemetry_url = telemetry_url.rstrip("/")
        self.api = api or self.request
        self.lock = RLock()

    @staticmethod
    def request(base, method, path, authorization, body=None):
        if not authorization.startswith("Bearer "):
            raise SetupError(401)
        raw = None if body is None else json.dumps(body).encode()
        request = Request(base + path, data=raw, method=method,
                          headers={"Authorization": authorization, "Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=8) as response:
                value = response.read()
                return json.loads(value) if value else None
        except HTTPError as error:
            raise SetupError(error.code if error.code in (400, 401, 403, 404, 409) else 503) from None
        except (URLError, TimeoutError, OSError):
            raise SetupError(503) from None

    def read(self):
        return json.loads(self.path.read_text(encoding="utf-8-sig"))

    def save(self, value):
        temporary = self.path.with_suffix(".provisioning.tmp")
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(value, stream, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.path)

    @staticmethod
    def identity(body):
        if body.get("mode") not in ("bus", "tram") or not re.fullmatch(r"[0-9]{4}-[0-9]{5}", str(body.get("vehicleKey", ""))):
            raise SetupError(400)
        key = body.get("creationKey")
        if not isinstance(key, str) or not 1 <= len(key) <= 160:
            raise SetupError(400)
        return body["mode"] + ":" + body["vehicleKey"]

    def reserve(self, body, authorization):
        if set(body) != {"mode", "vehicleKey", "creationKey"}:
            raise SetupError(400)
        identity = self.identity(body)
        account = self.api(self.device_url, "GET", "/accounts/current/scope", authorization)
        with self.lock:
            config = self.read()
            vehicles = config.get("vehicles", [])
            for binding in vehicles:
                if binding.get("mode", "tram") + ":" + binding["key"] == identity:
                    if binding.get("creationKey") != body["creationKey"] or binding.get("accountId") != account["id"]:
                        raise SetupError(409)
                    # Renew current device/source authorization before replay.
                    self.api(self.device_url, "GET", f'/devices/{binding["deviceId"]}/data-sources/{binding["dataSourceId"]}', authorization)
                    return {"available": True}
            reservations = config.setdefault("setupReservations", {})
            existing = reservations.get(identity)
            owner = {"creationKey": body["creationKey"], "accountId": account["id"]}
            if existing is not None and existing != owner:
                raise SetupError(409)
            if existing is None and len(vehicles) + len(reservations) >= 20:
                raise SetupError(409)
            reservations[identity] = owner
            self.save(config)
        return {"available": True}

    def bind(self, body, authorization):
        if set(body) != {"mode", "vehicleKey", "creationKey", "deviceId"}:
            raise SetupError(400)
        identity = self.identity(body)
        device_id = str(UUID(body["deviceId"]))
        device = self.api(self.device_url, "GET", "/devices/" + device_id, authorization)
        if device["type"] != "VEHICLE" or device["status"] != "ACTIVE":
            raise SetupError(409)
        account = self.api(self.device_url, "GET", "/accounts/current/scope", authorization)
        with self.lock:
            config = self.read()
            vehicles = config.get("vehicles", [])
            existing = next((v for v in vehicles if v.get("mode", "tram") + ":" + v["key"] == identity), None)
            if existing:
                if existing["deviceId"] != device_id or existing.get("creationKey") != body["creationKey"]:
                    raise SetupError(409)
                return self.status(device_id, authorization)
            reservation = config.get("setupReservations", {}).get(identity)
            if reservation != {"creationKey": body["creationKey"], "accountId": account["id"]} or len(vehicles) >= 20:
                raise SetupError(409)
            source_id = f'hsl-{body["mode"]}-{body["vehicleKey"]}'
            prefix = f"/devices/{device_id}/data-sources"
            sources = self.api(self.device_url, "GET", prefix, authorization)
            matches = [s for s in sources if s["externalId"] == source_id]
            if len(matches) > 1:
                raise SetupError(409)
            source = matches[0] if matches else self.api(self.device_url, "POST", prefix, authorization, {
                "sourceType": "API", "protocol": "REST", "externalId": source_id,
                "adapterConfig": {"provider": "HSL_HFP", "transportMode": body["mode"], "externalVehicleId": body["vehicleKey"], "attribution": "HSL HFP open data"}})
            if source["protocol"] != "REST" or source["sourceType"] != "API" or source.get("enabled") is False:
                raise SetupError(409)
            templates = self.api(self.device_url, "GET", "/devices/telemetry-templates", authorization)
            compatible = [t for t in templates if t["name"] == "HSL transport" and t.get("deviceType") == "VEHICLE"
                          and self.compatible_policy(t.get("policy", {}))]
            if not compatible:
                compatible = [self.api(self.device_url, "POST", "/devices/telemetry-templates", authorization,
                                       {"name": "HSL transport", "deviceType": "VEHICLE", "policy": self.default_policy()})]
            if len(compatible) != 1:
                raise SetupError(409)
            self.api(self.device_url, "POST", "/devices/telemetry-templates/assign", authorization,
                     {"templateId": compatible[0]["id"], "targets": [{"deviceId": device_id, "sourceId": source["id"]}]})
            token = self.api(self.device_url, "POST", prefix + f'/{source["id"]}/token', authorization)["token"]
            vehicles.append({"key": body["vehicleKey"], "mode": body["mode"], "deviceId": device_id,
                             "dataSourceId": source["id"], "sourceId": source_id, "sourceToken": token,
                             "creationKey": body["creationKey"], "accountId": account["id"]})
            config["vehicles"] = vehicles
            config["setupReservations"].pop(identity)
            self.save(config)
        return self.status(device_id, authorization)

    def delete_binding(self, device_id, supplied_token):
        device_id = str(UUID(device_id))
        token = os.environ.get("INTERNAL_SERVICE_TOKEN", "dev-internal-token")
        if not supplied_token or not hmac.compare_digest(token, supplied_token):
            raise SetupError(401)
        request = Request(self.device_url + "/internal/device-deletions/" + device_id,
                          headers={"X-Internal-Service-Token": token})
        try:
            with urlopen(request, timeout=8) as response:
                receipt = json.load(response)
        except (HTTPError, URLError, TimeoutError, OSError):
            raise SetupError(503) from None
        if receipt.get("deviceId") != device_id:
            raise SetupError(403)
        with self.lock:
            config = self.read()
            removed = [v for v in config.get("vehicles", []) if v["deviceId"] == device_id]
            config["vehicles"] = [v for v in config.get("vehicles", []) if v["deviceId"] != device_id]
            for binding in removed:
                config.get("setupReservations", {}).pop(binding.get("mode", "tram") + ":" + binding["key"], None)
            self.save(config)
        return {"status": "COMPLETED"}

    @staticmethod
    def default_policy():
        definitions = [("latitude", "number", "deg"), ("longitude", "number", "deg"),
                       ("speedKmh", "number", "km/h"), ("headingDeg", "number", "deg"),
                       ("transport.routeId", "string", ""), ("transport.routeShortName", "string", ""),
                       ("transport.direction", "number", ""), ("transport.destination", "string", ""),
                       ("transport.nextStopId", "string", ""), ("transport.scheduleDeviationSeconds", "number", "s"),
                       ("transport.doorsOpen", "boolean", ""), ("transport.accelerationMs2", "number", "m/s²"),
                       ("transport.scheduledStart", "string", ""), ("transport.locationSource", "string", "")]
        return {"fields": [{"path": path, "key": path, "label": path.split(".")[-1], "type": kind,
                            "unit": unit, "store": True, "display": path not in ("latitude", "longitude")}
                           for path, kind, unit in definitions]}

    @staticmethod
    def compatible_policy(policy):
        fields = {f.get("path"): f for f in policy.get("fields", [])}
        return all(fields.get(k, {}).get("store") is True for k in ("latitude", "longitude", "speedKmh")) and fields.get("transport.doorsOpen", {}).get("type") == "boolean"

    def status(self, device_id, authorization):
        device_id = str(UUID(device_id))
        self.api(self.device_url, "GET", "/devices/" + device_id, authorization)
        with self.lock:
            binding = next((v for v in self.read().get("vehicles", []) if v["deviceId"] == device_id), None)
        if not binding:
            raise SetupError(404)
        self.api(self.device_url, "GET", f'/devices/{device_id}/data-sources/{binding["dataSourceId"]}', authorization)
        evidence = self.api(self.telemetry_url, "GET", f'/telemetry/devices/{device_id}/positions/latest?sourceId={binding["sourceId"]}', authorization)
        return {"deviceId": device_id, "sourceId": binding["sourceId"], "dataSourceId": binding["dataSourceId"],
                "setupStatus": "TELEMETRY_READY" if evidence.get("freshness") == "FRESH" and evidence.get("position") else "WAITING_FOR_DATA",
                "freshness": evidence.get("freshness", "NO_DATA"), "provider": "HSL_HFP"}


def start_control(provisioning, port=8091, allow_setup=True):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # No tokens, request bodies or authorization in logs.

        def read_body(self):
            self.connection.settimeout(10)
            transfer = self.headers.get("Transfer-Encoding", "").lower()
            if transfer:
                if transfer != "chunked":
                    raise SetupError(400)
                chunks = []
                total = 0
                for _ in range(64):
                    line = self.rfile.readline(66)
                    if not re.fullmatch(rb"[0-9a-fA-F]{1,8}\r\n", line):
                        raise SetupError(400)
                    size = int(line.strip(), 16)
                    if size == 0:
                        if self.rfile.read(2) != b"\r\n":
                            raise SetupError(400)
                        return b"".join(chunks)
                    total += size
                    if total > 4096:
                        raise SetupError(400)
                    chunk = self.rfile.read(size)
                    if len(chunk) != size or self.rfile.read(2) != b"\r\n":
                        raise SetupError(400)
                    chunks.append(chunk)
                raise SetupError(400)
            size = int(self.headers.get("Content-Length", "0"))
            if not 1 <= size <= 4096:
                raise SetupError(400)
            return self.rfile.read(size)

        def handle_request(self):
            try:
                authorization = self.headers.get("Authorization", "")
                if self.command == "POST" and re.fullmatch(r"/internal/device-deletions/[0-9a-fA-F-]{36}", self.path):
                    result = provisioning.delete_binding(self.path.rsplit("/", 1)[1], self.headers.get("X-Internal-Service-Token"))
                elif not allow_setup:
                    raise SetupError(403)
                elif not authorization.startswith("Bearer "):
                    raise SetupError(401)
                elif self.command == "GET" and re.fullmatch(r"/bindings/[0-9a-fA-F-]{36}", self.path):
                    result = provisioning.status(self.path.rsplit("/", 1)[1], authorization)
                elif self.command == "POST" and self.path in ("/availability", "/bindings"):
                    body = json.loads(self.read_body())
                    if not isinstance(body, dict):
                        raise SetupError(400)
                    result = (provisioning.reserve if self.path == "/availability" else provisioning.bind)(body, authorization)
                else:
                    raise SetupError(404)
                raw = json.dumps(result).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            except SetupError as error:
                logging.getLogger("hsl.setup").warning("Setup request rejected: HTTP %s", error.status)
                self.send_error(error.status, "Device setup unavailable")
            except (ValueError, KeyError, TypeError):
                self.send_error(400, "Invalid setup request")
            except Exception:
                self.send_error(503, "Device setup unavailable")

        do_GET = handle_request
        do_POST = handle_request

    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    server.daemon_threads = True
    Thread(target=server.serve_forever, daemon=True).start()
    return server
