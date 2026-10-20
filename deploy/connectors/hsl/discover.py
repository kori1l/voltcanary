"""Find a bounded list of currently observed public vehicles for setup."""
import json
import sys
from threading import Event
import paho.mqtt.client as mqtt
from mapping import map_position


def discover(count=3, timeout=45, mode="tram", routes=()):
    if mode not in ("tram", "bus") or not 1 <= count <= 20:
        raise ValueError("Select tram/bus and 1-20 vehicles")
    found = {}
    done = Event()
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.tls_set()

    def connected(client, userdata, flags, reason, properties):
        if not reason.is_failure:
            client.subscribe(f"/hfp/v2/journey/ongoing/vp/{mode}/#")

    def message(client, userdata, message):
        point = map_position(message.topic, message.payload, retained=message.retain)
        if point and point.mode == mode and (not routes or point.line in routes) and (point.key in found or len(found) < count):
            found[point.key] = {"key": point.key, "line": point.line,
                                "timestamp": point.timestamp.isoformat(), "mode": mode}
        if len(found) >= count:
            done.set()

    client.on_connect = connected
    client.on_message = message
    client.connect("mqtt.hsl.fi", 8883, 30)
    client.loop_start()
    try:
        done.wait(timeout)
    finally:
        client.disconnect()
        client.loop_stop()
    if len(found) < count:
        raise RuntimeError(f"Not enough currently active GPS {mode} vehicles on selected routes; retry during service hours")
    return list(found.values())


if __name__ == "__main__":
    print(json.dumps(discover(int(sys.argv[1]) if len(sys.argv) > 1 else 3,
                             mode=sys.argv[2] if len(sys.argv) > 2 else "tram",
                             routes=sys.argv[3].split(",") if len(sys.argv) > 3 else ())))
