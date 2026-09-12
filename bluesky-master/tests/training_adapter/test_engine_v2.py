import hashlib
import unittest

from bluesky.plugins.training_adapter.engine_v2 import EngineV2Handlers


class FakeEngine(object):
    def __init__(self):
        self.aircraft = {}
        self.points = []
        self.paused = False

    def health(self):
        return {"connected": True}

    def pause(self):
        self.paused = True

    def snapshot(self):
        return {"simulationTimeSeconds": 123.5, "engineState": "PAUSED"}

    def sync_reference_data(self, payload):
        self.points = payload["points"]
        return {"totalCount": len(payload["points"])}

    def create_aircraft(self, payload):
        callsign = payload["callsign"].upper()
        snapshot = {key: payload[key] for key in (
            "aircraftType", "latitude", "longitude", "headingDegrees",
            "altitudeFeet", "speedKnots")}
        snapshot["callsign"] = callsign
        self.aircraft[callsign] = snapshot
        return snapshot

    def aircraft_snapshot(self, callsign):
        if callsign not in self.aircraft:
            raise ValueError("航空器不存在: " + callsign)
        return dict(self.aircraft[callsign])


def aircraft_payload():
    return {"aircraftId": "a-1", "callsign": "CSN1", "aircraftType": "A320",
            "latitude": 23.4, "longitude": 113.3, "headingDegrees": 20.0,
            "altitudeFeet": 8000.0, "speedKnots": 250.0,
            "origin": "ZGGG", "destination": "ZBAA", "route": ["ZGGG", "ZBAA"]}


class EngineV2HandlersTest(unittest.TestCase):

    def test_hello_queries_real_engine_aircraft_type_catalog(self):
        from unittest.mock import Mock
        engine=FakeEngine()
        engine.search_reference=Mock(return_value={"items":[{"code":"B738"}]})
        reply=EngineV2Handlers(engine).hello({"aircraftTypeQuery":"B738"})
        self.assertEqual([{"code":"B738"}],reply["aircraftTypes"])
        engine.search_reference.assert_called_once_with({"kind":"AIRCRAFT_TYPE","query":"B738","limit":50})

    def test_native_command_maps_nested_parameters_and_identity(self):
        from unittest.mock import Mock
        import json
        engine=FakeEngine();engine.execute_instruction=Mock(return_value={"accepted":True})
        handlers=EngineV2Handlers(engine)
        handlers.handlers()["INSTRUCTION_APPLY"]({"instructionId":"i-42","callsign":"CSN1","type":"HDG","parameters":{"magneticHeadingDeg":123},"affectedChannels":["LATERAL"]})
        payload=engine.execute_instruction.call_args[0][0]
        self.assertEqual("i-42",payload["commandId"])
        self.assertEqual(123,payload["headingDegrees"])
        self.assertEqual(123,json.loads(payload["parametersJson"])["magneticHeadingDeg"])

    def test_existence_failure_is_not_absence(self):
        from unittest.mock import Mock
        engine=FakeEngine();engine.aircraft_snapshot=Mock(side_effect=RuntimeError("snapshot unavailable"))
        with self.assertRaises(RuntimeError):
            EngineV2Handlers(engine).handlers()["AIRCRAFT_EXISTS_GET"]({"callsign":"CSN1"})

    def test_apply_aircraft_is_replay_safe_and_rejects_mismatched_existing_entity(self):
        engine = FakeEngine()
        handlers = EngineV2Handlers(engine)

        first = handlers.apply_aircraft(aircraft_payload())
        second = handlers.apply_aircraft(aircraft_payload())

        self.assertFalse(first["alreadyExisted"])
        self.assertTrue(second["alreadyExisted"])
        self.assertEqual(1, len(engine.aircraft))
        mismatched = aircraft_payload()
        mismatched["altitudeFeet"] = 9000.0
        with self.assertRaisesRegex(ValueError, "状态与重放请求不一致"):
            handlers.apply_aircraft(mismatched)

    def test_reference_snapshot_load_verifies_checksum_and_normalizes_points(self):
        engine = FakeEngine()
        handlers = EngineV2Handlers(engine)
        manifest = '{"schemaVersion":"reference-manifest/1"}'
        payload = {
            "manifestJson": manifest,
            "manifestChecksum": hashlib.sha256(manifest.encode("utf-8")).hexdigest(),
            "resources": {
                "airports": [{"id": "apt-1", "code": "ZGGG",
                              "latitudeDeg": 23.4, "longitudeDeg": 113.3}],
                "navaids": [{"id": "nav-1", "code": "P47", "type": "VOR",
                             "latitude": 24.0, "longitude": 114.0}],
            },
        }

        result = handlers.load_reference_snapshot(payload)

        self.assertTrue(result["accepted"])
        self.assertEqual(2, result["pointCount"])
        self.assertEqual("AIRPORT", engine.points[0]["type"])
        bad = dict(payload)
        bad["manifestChecksum"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "checksum"):
            handlers.load_reference_snapshot(bad)

    def test_pause_carries_actual_pause_simulation_time(self):
        """评审 A5：PAUSED 响应必须携带实际暂停时刻（详细设计 4.2.7）。"""
        engine = FakeEngine()
        handlers = EngineV2Handlers(engine)

        result = handlers.pause({})

        self.assertTrue(engine.paused)
        self.assertTrue(result["accepted"])
        self.assertEqual(123.5, result["actualPauseSimulationTimeSeconds"])
        self.assertEqual(123.5, result["simulationTimeSeconds"])


if __name__ == "__main__":
    unittest.main()
