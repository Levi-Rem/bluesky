import unittest

from bluesky.plugins.training_adapter.protocol import AdapterProtocol


class FakeEngine:
    def __init__(self):
        self.operations = []

    def health(self):
        return {
            "connected": True,
            "status": "CONNECTED",
            "performanceModel": "OPENAP",
            "message": "BlueSky 已连接",
        }

    def start(self):
        self.operations.append("START")

    def pause(self):
        self.operations.append("PAUSE")

    def resume(self):
        self.operations.append("RESUME")

    def reset(self):
        self.operations.append("RESET")
        return {"engineState": "READY"}

    def search_reference(self, payload):
        self.operations.append(("REFERENCE_SEARCH", payload))
        return {"items": [{"code": "ZSSS", "name": "SHANGHAI HONGQIAO"}]}

    def create_aircraft(self, payload):
        self.operations.append(("AIRCRAFT_CREATE", payload))
        return {"callsign": payload["callsign"]}

    def delete_aircraft(self, callsign):
        self.operations.append(("AIRCRAFT_DELETE", callsign))
        return {"callsign": callsign, "deleted": True}

    def snapshot(self):
        return {
            "simulationTimeSeconds": 12.5,
            "engineState": "RUNNING",
            "aircraft": [],
        }

    def execute_instruction(self, payload):
        if payload.get("waypoint") == "UNKNOWN":
            raise ValueError("未知航路点: UNKNOWN")
        self.operations.append(("INSTRUCTION_EXECUTE", payload))
        return {"callsign": payload["callsign"], "accepted": True}


class AdapterProtocolTest(unittest.TestCase):
    def test_ping_returns_versioned_engine_health(self):
        protocol = AdapterProtocol(FakeEngine())

        response = protocol.handle(
            {
                "protocolVersion": "1.0",
                "requestId": "request-1",
                "type": "PING",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": {},
            }
        )

        self.assertEqual("1.0", response["protocolVersion"])
        self.assertEqual("request-1", response["requestId"])
        self.assertTrue(response["success"])
        self.assertEqual("OK", response["code"])
        self.assertEqual("OPENAP", response["payload"]["performanceModel"])

    def test_lifecycle_requests_reach_engine_in_order(self):
        engine = FakeEngine()
        protocol = AdapterProtocol(engine)

        for message_type in ("START", "PAUSE", "RESUME"):
            response = protocol.handle(
                {
                    "protocolVersion": "1.0",
                    "requestId": message_type.lower(),
                    "type": message_type,
                    "exerciseGroupId": "GROUP-DEFAULT",
                    "payload": {},
                }
            )
            self.assertTrue(response["success"])

        self.assertEqual(["START", "PAUSE", "RESUME"], engine.operations)

    def test_reset_request_reaches_engine(self):
        engine = FakeEngine()
        response = AdapterProtocol(engine).handle(
            {
                "protocolVersion": "1.0",
                "requestId": "reset-1",
                "type": "RESET",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": {},
            }
        )

        self.assertTrue(response["success"])
        self.assertEqual("READY", response["payload"]["engineState"])
        self.assertEqual(["RESET"], engine.operations)

    def test_reference_search_request_reaches_engine(self):
        engine = FakeEngine()
        payload = {"kind": "AIRPORT", "query": "zss", "limit": 20}
        response = AdapterProtocol(engine).handle(
            {
                "protocolVersion": "1.0",
                "requestId": "reference-1",
                "type": "REFERENCE_SEARCH",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": payload,
            }
        )

        self.assertTrue(response["success"])
        self.assertEqual("ZSSS", response["payload"]["items"][0]["code"])
        self.assertEqual(("REFERENCE_SEARCH", payload), engine.operations[-1])

    def test_aircraft_create_payload_reaches_engine(self):
        engine = FakeEngine()
        protocol = AdapterProtocol(engine)
        payload = {
            "callsign": "CCA3582",
            "aircraftType": "A320",
            "latitude": 31.1434,
            "longitude": 121.8052,
            "headingDegrees": 360,
            "altitudeFeet": 9000,
            "speedKnots": 250,
            "route": ["CEN", "CON", "ZBAA"],
        }

        response = protocol.handle(
            {
                "protocolVersion": "1.0",
                "requestId": "create-1",
                "type": "AIRCRAFT_CREATE",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": payload,
            }
        )

        self.assertTrue(response["success"])
        self.assertEqual("CCA3582", response["payload"]["callsign"])
        self.assertEqual(("AIRCRAFT_CREATE", payload), engine.operations[-1])

    def test_delete_and_snapshot_requests_reach_engine(self):
        engine = FakeEngine()
        protocol = AdapterProtocol(engine)

        deleted = protocol.handle(
            {
                "protocolVersion": "1.0",
                "requestId": "delete-1",
                "type": "AIRCRAFT_DELETE",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": {"callsign": "CCA3582"},
            }
        )
        snapshot = protocol.handle(
            {
                "protocolVersion": "1.0",
                "requestId": "snapshot-1",
                "type": "SNAPSHOT_GET",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": {},
            }
        )

        self.assertTrue(deleted["payload"]["deleted"])
        self.assertEqual(("AIRCRAFT_DELETE", "CCA3582"), engine.operations[-1])
        self.assertEqual("RUNNING", snapshot["payload"]["engineState"])

    def test_instruction_payload_reaches_engine(self):
        engine = FakeEngine()
        protocol = AdapterProtocol(engine)
        payload = {
            "callsign": "CCA3582",
            "type": "HDG",
            "headingDegrees": 90.0,
            "route": [],
        }

        response = protocol.handle(
            {
                "protocolVersion": "1.0",
                "requestId": "instruction-1",
                "type": "INSTRUCTION_EXECUTE",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": payload,
            }
        )

        self.assertTrue(response["success"])
        self.assertTrue(response["payload"]["accepted"])
        self.assertEqual(("INSTRUCTION_EXECUTE", payload), engine.operations[-1])

    def test_engine_rejection_returns_failure_without_breaking_later_requests(self):
        protocol = AdapterProtocol(FakeEngine())

        rejected = protocol.handle(
            {
                "protocolVersion": "1.0",
                "requestId": "bad-dct",
                "type": "INSTRUCTION_EXECUTE",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": {
                    "callsign": "CCA3582",
                    "type": "DCT",
                    "waypoint": "UNKNOWN",
                },
            }
        )
        ping = protocol.handle(
            {
                "protocolVersion": "1.0",
                "requestId": "after-error",
                "type": "PING",
                "exerciseGroupId": "GROUP-DEFAULT",
                "payload": {},
            }
        )

        self.assertFalse(rejected["success"])
        self.assertEqual("ENGINE_REJECTED", rejected["code"])
        self.assertIn("UNKNOWN", rejected["message"])
        self.assertTrue(ping["success"])


if __name__ == "__main__":
    unittest.main()
