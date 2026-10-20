"""A bounded latest-per-vehicle mailbox; MQTT and HTTP use separate threads."""
from threading import Lock
from mapping import fresh


class LatestPositions:
    def __init__(self, keys):
        self.keys = frozenset(keys)
        self.pending = {}
        self.accepted = {}
        self.lock = Lock()

    def offer(self, observation):
        if observation is None or observation.binding_key not in self.keys:
            return False
        with self.lock:
            previous = self.pending.get(observation.binding_key)
            watermark = self.accepted.get(observation.binding_key)
            if watermark is not None and observation.timestamp <= watermark:
                return False
            if previous is not None and observation.timestamp <= previous.timestamp:
                return False
            self.pending[observation.binding_key] = observation
            return True

    def ready(self, now):
        with self.lock:
            self.pending = {key: point for key, point in self.pending.items() if fresh(point.timestamp, now)}
            return list(self.pending.values())

    def acknowledge(self, observation):
        with self.lock:
            self.accepted[observation.binding_key] = observation.timestamp
            pending = self.pending.get(observation.binding_key)
            if pending is not None and pending.timestamp <= observation.timestamp:
                self.pending.pop(observation.binding_key)
