"""HSL public MQTT -> authenticated VoltCanary REST ingress, no simulation."""
import json
import logging
import os
import re
import signal
import time
from datetime import datetime, timezone
from pathlib import Path
from threading import Event
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID

import paho.mqtt.client as mqtt
from buffer import LatestPositions
from mapping import map_position

LOG = logging.getLogger("hsl")
STATUS_PATH = Path("/tmp/hsl-status.json")


def load_bindings(path):
    config = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    vehicles = config.get("vehicles", [])
    if not 1 <= len(vehicles) <= 20:
        raise ValueError("Configure 1-20 explicit vehicles")
    bindings = {}
    for item in vehicles:
        key = item["key"]
        mode = item.get("mode", "tram")
        identity = key if mode == "tram" else f"{mode}:{key}"
        if mode not in ("tram", "bus") or not re.fullmatch(r"[0-9]{4}-[0-9]{5}", key) or identity in bindings:
            raise ValueError("Invalid or duplicate vehicle identity")
        UUID(item["deviceId"])
        if item["sourceId"] != f"hsl-{mode}-" + key or not item.get("sourceToken"):
            raise ValueError("Invalid source binding")
        bindings[identity] = dict(item, mode=mode)
    return bindings


def forward(url, binding, point):
    body = {"deviceId": binding["deviceId"], "sourceId": binding["sourceId"],
            "timestamp": point.timestamp.isoformat(), "metrics": point.metrics}
    request = Request(url, data=json.dumps(body, allow_nan=False).encode("utf-8"),
                      headers={"Content-Type": "application/json",
                               "Authorization": "Bearer " + binding["sourceToken"]}, method="POST")
    try:
        with urlopen(request, timeout=3) as response:
            return response.status == 202
    except HTTPError as error:
        # Do not log request headers, response bodies or credentials.
        LOG.warning("Ingress rejected %s: HTTP %s", point.key, error.code)
    except (URLError, TimeoutError, OSError):
        LOG.warning("Ingress unavailable for %s", point.key)
    return False


def run():
    bindings = load_bindings(os.environ.get("HSL_CONFIG_PATH", "/run/hsl/config.json"))
    mailbox = LatestPositions(bindings)
    stop = Event()
    connected = Event()
    accepted = 0
    last_accepted = None
    url = os.environ.get("HSL_INGESTION_URL", "http://ingestion-service:8083/ingestion/readings")
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.tls_set()  # default trust store, certificate/hostname verification enabled
    client.reconnect_delay_set(min_delay=1, max_delay=30)

    def on_connect(client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            LOG.warning("MQTT connection rejected")
            return
        connected.set()
        for binding in bindings.values():
            operator, vehicle = binding["key"].split("-")
            client.subscribe(f"/hfp/v2/journey/ongoing/vp/{binding['mode']}/{operator}/{vehicle}/#", qos=0)
        LOG.info("Connected to HSL; following %s explicit vehicles", len(bindings))

    def on_disconnect(client, userdata, disconnect_flags, reason_code, properties):
        connected.clear()
        LOG.warning("HSL disconnected; waiting for MQTT reconnect")

    def on_message(client, userdata, message):
        mailbox.offer(map_position(message.topic, message.payload, retained=message.retain))

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_message = on_message
    for event in (signal.SIGTERM, signal.SIGINT):
        signal.signal(event, lambda *_: stop.set())
    client.connect_async("mqtt.hsl.fi", 8883, keepalive=30)
    client.loop_start()
    LOG.info("Starting HSL live connector")
    try:
        while not stop.is_set():
            started = time.monotonic()
            for point in mailbox.ready(datetime.now(timezone.utc)):
                if stop.is_set():
                    break
                if forward(url, bindings[point.binding_key], point):
                    mailbox.acknowledge(point)
                    accepted += 1
                    last_accepted = datetime.now(timezone.utc).isoformat()
            status = {"connected": connected.is_set(), "accepted": accepted,
                      "lastAcceptedAt": last_accepted, "heartbeat": time.time(),
                      "pending": len(mailbox.ready(datetime.now(timezone.utc)))}
            temporary = STATUS_PATH.with_suffix(".tmp")
            temporary.write_text(json.dumps(status), encoding="utf-8")
            temporary.replace(STATUS_PATH)
            stop.wait(max(0, 5 - (time.monotonic() - started)))
    finally:
        client.disconnect()
        client.loop_stop()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run()
