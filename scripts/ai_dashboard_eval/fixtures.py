"""Disposable measured vehicle fixtures through ordinary source/ingestion APIs."""
from datetime import datetime, timedelta, timezone
import uuid

from .api import ApiError, wait_until


def iso(value):
    return value.isoformat().replace("+00:00", "Z")


class VehicleFixture:
    def __init__(self, client, marker, profile="normal"):
        self.api, self.marker, self.profile = client, marker, profile
        self.devices, self.sources, self.archived = [], [], []
        self.template = None

    def __enter__(self):
        try:
            self.create()
            return self
        except Exception:
            self.cleanup_failures = self.cleanup()
            raise

    def create(self):
        total = 2 if self.profile == "pair" else 1
        for i in range(total):
            device = self.api.call("POST", "/devices", {"name": self.marker + ("_B" if i else ""),
                "type": "VEHICLE"}, {"Idempotency-Key": str(uuid.uuid4())})
            self.devices.append(device["id"])
            count = 2 if self.profile == "ambiguous" else 1
            for index in range(count):
                source = self.api.call("POST", f"/devices/{device['id']}/data-sources", {
                    "sourceType": "SENSOR", "protocol": "REST",
                    "externalId": "eval-" + str(uuid.uuid4())})
                credential = self.api.call("POST", f"/devices/{device['id']}/data-sources/{source['id']}/token")
                self.sources.append({"deviceId": device["id"], "id": source["id"],
                                     "externalId": source["externalId"], "token": credential["token"]})
        self.refresh()
        for device in self.devices:
            wait_until(lambda: self.api.call("GET", f"/telemetry/devices/{device}/fields"),
                       lambda fields: {"speedKmh", "transport.doorsOpen", "transport.nextStopId"}
                       <= {f["key"] for f in fields}, seconds=60)

    def refresh(self):
        now = datetime.now(timezone.utc)
        offset = timedelta(hours=2) if self.profile in ("stale", "empty") else timedelta()
        for source in self.sources:
            # Known observations, including an explicit missing door state and a gap.
            for i, seconds in enumerate((420, 415, 410, 405, 180, 175, 170, 5, 0)):
                transport = {"nextStopId": "1473180", "destination": "Synthetic Helsinki fixture",
                             "routeShortName": "QA", "doorsOpen": i % 2 == 1}
                if i == 3:
                    transport.pop("doorsOpen")
                metrics = {"latitude": 60.17 + i * 0.0001, "longitude": 24.94 + i * 0.0001,
                           "speedKmh": 0 if i in (0, 8) else 15 + i, "headingDeg": 90,
                           "transport": transport}
                body = {"deviceId": source["deviceId"], "sourceId": source["externalId"],
                        "timestamp": iso(now - offset - timedelta(seconds=seconds)), "metrics": metrics}
                wait_until(lambda: self.ingest(body, source["token"]), bool, seconds=45)

    def ingest(self, body, token):
        try:
            result = self.api.call("POST", "/ingestion/readings", body, token=token)
            return result.get("acceptedCount", 0) >= 1
        except ApiError as error:
            if error.status not in (401, 403, 404, 409, 503):
                raise
            return False  # Source outbox/runtime propagation is asynchronous.

    def metadata(self):
        return {"synthetic": True, "profile": self.profile, "deviceIds": self.devices,
                "sources": [{k: v for k, v in s.items() if k != "token"} for s in self.sources],
                "observations": {"speedKmh": 0, "doorsOpen": False, "nextStopId": "1473180",
                                 "hasGap": True, "hasMissingDoorObservation": True}}

    def revoke(self):
        for device in self.devices:
            result = self.api.call("POST", f"/devices/{device}/decommission")
            if result.get("status") != "DECOMMISSIONED":
                raise ApiError(0, "FIXTURE_REVOCATION_NOT_CONFIRMED")
            self.archived.append(device)

    def cleanup(self):
        failures = []
        for device in self.devices:
            if device in self.archived:
                continue
            try:
                self.api.call("POST", f"/devices/{device}/decommission")
                self.archived.append(device)
            except ApiError as error:
                failures.append({"deviceId": device, "status": error.status})
        # Source credentials only exist in process memory and are cleared here.
        for source in self.sources:
            source.pop("token", None)
        return failures

    def __exit__(self, *_):
        self.cleanup_failures = self.cleanup()
