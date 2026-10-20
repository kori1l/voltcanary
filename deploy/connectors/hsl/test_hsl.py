import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

from buffer import LatestPositions
from main import forward, load_bindings
from mapping import map_position

NOW = datetime(2026, 10, 11, 19, 0, tzinfo=timezone.utc)
TOPIC = "/hfp/v2/journey/ongoing/vp/tram/0040/00442/1001/1/Terminus/#"


def sample(**changes):
    data = {"lat": 60.18, "long": 24.92, "spd": 10, "hdg": 360,
            "tst": NOW.isoformat(), "loc": "GPS", "oper": 99, "veh": 442,
            "odo": 900, "desi": "1"}
    data.update(changes)
    return json.dumps({"VP": data}).encode()


class MappingTests(unittest.TestCase):
    def test_identity_units_and_no_fabricated_metrics(self):
        point = map_position(TOPIC, sample(), NOW)
        self.assertEqual("0040-00442", point.key)  # topic owner, not payload operator
        self.assertEqual(NOW, point.timestamp)
        self.assertEqual({"latitude": 60.18, "longitude": 24.92,
                          "speedKmh": 36, "headingDeg": 0}, {key:value for key,value in point.metrics.items() if key!="transport"})
        self.assertEqual(point.key, map_position(TOPIC.replace("1001", "1009"), sample(), NOW).key)

    def test_journey_context_uses_observed_topic_fields_and_preserves_false_and_zero(self):
        topic = TOPIC.replace("Terminus/#", "Käpylä/22:15/1234567/#")
        point = map_position(topic, sample(dl=0, drst=0, acc=-0.5), NOW)
        context = point.metrics["transport"]
        self.assertEqual("1001", context["routeId"])
        self.assertEqual("Käpylä", context["destination"])
        self.assertEqual("1234567", context["nextStopId"])
        self.assertEqual("22:15", context["scheduledStart"])
        self.assertEqual(0, context["scheduleDeviationSeconds"])
        self.assertFalse(context["doorsOpen"])
        self.assertEqual(-0.5, context["accelerationMs2"])
        missing = map_position(topic, sample(dl=None, drst=None), NOW).metrics["transport"]
        self.assertNotIn("doorsOpen", missing)
        self.assertNotIn("scheduleDeviationSeconds", missing)

    def test_invalid_or_non_gps_observations_are_skipped(self):
        for changes in ({"lat": None}, {"long": 181}, {"lat": True}, {"lat": float("nan")},
                        {"loc": "DR"}, {"tst": "bad"}, {"tst": "2026-10-11T19:00:00"},
                        {"tst": (NOW - timedelta(seconds=121)).isoformat()},
                        {"tst": (NOW + timedelta(seconds=16)).isoformat()}):
            with self.subTest(changes=changes):
                self.assertIsNone(map_position(TOPIC, sample(**changes), NOW))
        self.assertIsNone(map_position(TOPIC, b"[]", NOW))
        self.assertIsNone(map_position(TOPIC, b"x" * 16385, NOW))
        self.assertIsNone(map_position(TOPIC, sample(), NOW, retained=True))
        self.assertIsNone(map_position(TOPIC.replace("tram", "train"), sample(), NOW))

    def test_bus_context_and_mode_identity_are_preserved(self):
        point = map_position(TOPIC.replace("tram", "bus"), sample(desi="97N", drst=1), NOW)
        self.assertEqual("bus", point.mode)
        self.assertEqual("bus:0040-00442", point.binding_key)
        self.assertEqual("97N", point.metrics["transport"]["routeShortName"])
        self.assertTrue(point.metrics["transport"]["doorsOpen"])
        tram_box = LatestPositions([point.key])
        self.assertFalse(tram_box.offer(point))
        bus_box = LatestPositions([point.binding_key])
        self.assertTrue(bus_box.offer(point))
        self.assertFalse(bus_box.offer(map_position(TOPIC, sample(), NOW)))

    def test_unknown_optional_values_are_omitted(self):
        for speed, heading in ((None, None), (-1, 361), (True, False), (float("inf"), "0")):
            point = map_position(TOPIC, sample(spd=speed, hdg=heading), NOW)
            self.assertEqual({"latitude", "longitude"}, set(point.metrics)-{"transport"})


class MailboxTests(unittest.TestCase):
    def test_bounded_latest_ordering_and_ack_race(self):
        box = LatestPositions(["0040-00442"])
        original = map_position(TOPIC, sample(), NOW)
        later = map_position(TOPIC, sample(tst=(NOW + timedelta(seconds=1)).isoformat()), NOW)
        self.assertTrue(box.offer(original))
        self.assertFalse(box.offer(original))
        self.assertFalse(box.offer(map_position(TOPIC.replace("00442", "00443"), sample(), NOW)))
        self.assertTrue(box.offer(later))
        self.assertEqual([later], box.ready(NOW))
        box.acknowledge(original)  # concurrent new arrival must survive older HTTP acknowledgement
        self.assertEqual([later], box.ready(NOW))
        box.acknowledge(later)
        self.assertFalse(box.offer(original))
        self.assertEqual([], box.ready(NOW))

    def test_failure_retains_only_fresh_latest(self):
        box = LatestPositions(["0040-00442"])
        point = map_position(TOPIC, sample(), NOW)
        box.offer(point)
        self.assertEqual([point], box.ready(NOW))
        self.assertEqual([point], box.ready(NOW + timedelta(seconds=30)))
        self.assertEqual([], box.ready(NOW + timedelta(seconds=121)))


class BoundaryTests(unittest.TestCase):
    def test_config_rejects_ambiguous_or_invalid_bindings(self):
        binding = {"key": "0040-00442", "deviceId": "cd7dcd47-7891-4ce7-81cb-00a30ceef0d2",
                   "sourceId": "hsl-tram-0040-00442", "sourceToken": "test-only"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text(json.dumps({"vehicles": [binding]}))
            self.assertEqual({"0040-00442"}, set(load_bindings(path)))
            bus = dict(binding, mode="bus", sourceId="hsl-bus-0040-00442")
            path.write_text(json.dumps({"vehicles": [binding, bus]}))
            self.assertEqual({"0040-00442", "bus:0040-00442"}, set(load_bindings(path)))
            for values in ([], [binding] * 21, [binding, binding],
                           [dict(binding, sourceId="foreign")], [dict(binding, sourceToken="")],
                           [dict(binding, mode="bus")], [dict(binding, mode="train")]):
                path.write_text(json.dumps({"vehicles": values}))
                with self.assertRaises(ValueError):
                    load_bindings(path)

    def test_http_forward_uses_source_auth_and_preserves_observation(self):
        binding = {"deviceId": "device", "sourceId": "source", "sourceToken": "test-only"}
        point = map_position(TOPIC, sample(), NOW)
        with patch("main.urlopen") as open_request:
            open_request.return_value.__enter__.return_value.status = 202
            self.assertTrue(forward("http://ingress/readings", binding, point))
            request = open_request.call_args.args[0]
            self.assertEqual("Bearer test-only", request.get_header("Authorization"))
            self.assertEqual(NOW.isoformat(), json.loads(request.data)["timestamp"])
            open_request.side_effect = HTTPError(request.full_url, 503, "Unavailable", {}, None)
            self.assertFalse(forward(request.full_url, binding, point))


if __name__ == "__main__":
    unittest.main()
