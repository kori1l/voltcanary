"""Pure mapping of public HSL HFP observations. Identity comes from the topic."""
import json
import math
import re
from dataclasses import dataclass
from datetime import datetime, timezone


@dataclass(frozen=True)
class Observation:
    key: str
    timestamp: datetime
    metrics: dict
    line: str
    mode: str = "tram"

    @property
    def binding_key(self):
        return self.key if self.mode == "tram" else f"{self.mode}:{self.key}"


def numeric(value, minimum, maximum):
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and minimum <= value <= maximum)


def fresh(timestamp, now):
    return -15 <= (now - timestamp).total_seconds() <= 120


def map_position(topic, payload, now=None, retained=False):
    """Return None for unsupported, malformed or non-current observations."""
    if retained or len(payload) > 16384:
        return None
    parts = topic.split("/")
    if len(parts) < 9 or parts[:6] != ["", "hfp", "v2", "journey", "ongoing", "vp"] or parts[6] not in ("tram", "bus"):
        return None
    operator, vehicle = parts[7:9]
    if len(operator) != 4 or len(vehicle) != 5 or not operator.isascii() or not vehicle.isascii():
        return None
    if not operator.isdigit() or not vehicle.isdigit():
        return None
    try:
        vp = json.loads(payload)["VP"]
        if not isinstance(vp, dict) or vp.get("loc") != "GPS":
            return None
        if not numeric(vp.get("lat"), -90, 90) or not numeric(vp.get("long"), -180, 180):
            return None
        timestamp = datetime.fromisoformat(vp["tst"].replace("Z", "+00:00"))
        if timestamp.utcoffset() is None:
            return None
        timestamp = timestamp.astimezone(timezone.utc)
        if not fresh(timestamp, now or datetime.now(timezone.utc)):
            return None
        metrics = {"latitude": vp["lat"], "longitude": vp["long"]}
        if numeric(vp.get("spd"), 0, 300 / 3.6):
            metrics["speedKmh"] = round(vp["spd"] * 3.6, 4)
        if numeric(vp.get("hdg"), 0, 360):
            metrics["headingDeg"] = vp["hdg"] % 360
        context = {}
        def text(field, value, maximum):
            if isinstance(value, str) and value.strip() and len(value) <= maximum and not any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value):
                context[field] = value
        def topic_part(index):
            return parts[index] if len(parts) > index and parts[index] not in ("", "#", "+") else None
        text("routeId", vp.get("route") or topic_part(9), 128)
        text("routeShortName", vp.get("desi"), 64)
        text("destination", topic_part(11), 200)
        text("nextStopId", topic_part(13), 128)
        direction = vp.get("dir") or topic_part(10)
        if str(direction) in ("1", "2"):
            context["direction"] = int(direction)
        if numeric(vp.get("dl"), -86400, 86400) and vp["dl"] % 1 == 0:
            context["scheduleDeviationSeconds"] = int(vp["dl"])
        if numeric(vp.get("drst"), 0, 1) and vp["drst"] % 1 == 0:
            context["doorsOpen"] = vp["drst"] == 1
        if numeric(vp.get("acc"), -30, 30):
            context["accelerationMs2"] = vp["acc"]
        start = vp.get("start") or topic_part(12)
        if isinstance(start, str) and re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", start):
            context["scheduledStart"] = start
        context["locationSource"] = vp["loc"]
        metrics["transport"] = context
        return Observation(f"{operator}-{vehicle}", timestamp, metrics, str(vp.get("desi") or ""), parts[6])
    except (ValueError, TypeError, KeyError, AttributeError, OverflowError):
        return None
