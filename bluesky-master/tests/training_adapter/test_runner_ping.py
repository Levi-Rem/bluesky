import json
import socket
import subprocess
import sys
import time
import unittest
from pathlib import Path

import zmq


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def free_tcp_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as candidate:
        candidate.bind(("127.0.0.1", 0))
        return candidate.getsockname()[1]


class AdapterRunnerPingTest(unittest.TestCase):
    def test_runner_embeds_real_bluesky_and_answers_ping(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "bluesky.plugins.training_adapter.runner",
                "--control-endpoint",
                control_endpoint,
                "--state-endpoint",
                state_endpoint,
                "--workdir",
                str(REPOSITORY_ROOT),
            ],
            cwd=str(REPOSITORY_ROOT),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        context = zmq.Context()
        try:
            response = self._wait_for_ping(context, control_endpoint, process)
            self.assertTrue(response["success"])
            self.assertTrue(response["payload"]["connected"])
            self.assertEqual("OPENAP", response["payload"]["performanceModel"])
            self.assertEqual(
                {
                    "sourceDatabase": "simulator_backup",
                    "scope": "AIRBORNE_ONLY",
                    "loadType": "MEDIUM",
                    "aircraftTypeCount": 9,
                },
                response["payload"]["airbornePerformance"],
            )
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            context.term()

    def test_state_frames_identify_adapter_instance(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        subscriber = context.socket(zmq.SUB)
        subscriber.linger = 0
        subscriber.rcvtimeo = 5000
        subscriber.setsockopt(zmq.SUBSCRIBE, b"state.GROUP-DEFAULT")
        subscriber.connect(state_endpoint)
        try:
            self._wait_for_ping(context, control_endpoint, process)
            _topic, body = subscriber.recv_multipart()
            frame = json.loads(body.decode("utf-8"))
            self.assertTrue(frame["instanceId"])
            self.assertGreater(frame["sequence"], 0)
        finally:
            subscriber.close()
            self._stop_runner(process)
            context.term()

    def test_runner_creates_aircraft_in_real_bluesky(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            response = self._request(
                context,
                control_endpoint,
                {
                    "protocolVersion": "1.0",
                    "requestId": "create-real",
                    "type": "AIRCRAFT_CREATE",
                    "exerciseGroupId": "GROUP-DEFAULT",
                    "payload": {
                        "callsign": "CCA3582",
                        "aircraftType": "A320",
                        "origin": "ZSSS",
                        "destination": "ZBAA",
                        "latitude": 31.1434,
                        "longitude": 121.8052,
                        "headingDegrees": 360,
                        "altitudeFeet": 9000,
                        "speedKnots": 250,
                        "route": ["ZSSS", "ZBAA"],
                    },
                },
            )

            self.assertTrue(response["success"], response)
            aircraft = response["payload"]
            self.assertEqual("CCA3582", aircraft["callsign"])
            self.assertAlmostEqual(9000, aircraft["altitudeFeet"], delta=1)
            self.assertAlmostEqual(250, aircraft["speedKnots"], delta=1)
            self.assertIn("mach", aircraft)
            self.assertGreater(aircraft["mach"], 0)
            self.assertEqual(["ZSSS", "ZBAA"], aircraft["route"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_create_rejects_aircraft_outside_imported_speed_envelope(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            payload = self._valid_aircraft_payload()
            payload.update({"altitudeFeet": 6000 / 0.3048, "speedKnots": 350})

            response = self._request(
                context,
                control_endpoint,
                self._message("create-outside-envelope", "AIRCRAFT_CREATE", payload),
            )

            self.assertFalse(response["success"], response)
            self.assertIn("速度包线", response["message"])
            snapshot = self._request(
                context,
                control_endpoint,
                self._message("snapshot-after-envelope-rejection", "SNAPSHOT_GET", {}),
            )
            self.assertEqual([], snapshot["payload"]["aircraft"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_create_rejects_aircraft_above_imported_ceiling(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            payload = self._valid_aircraft_payload()
            payload.update({"altitudeFeet": 42000, "speedKnots": 250})

            response = self._request(
                context,
                control_endpoint,
                self._message("create-above-ceiling", "AIRCRAFT_CREATE", payload),
            )

            self.assertFalse(response["success"], response)
            self.assertIn("升限", response["message"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_created_aircraft_does_not_start_simulation_before_start_request(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            created = self._request(
                context,
                control_endpoint,
                self._message(
                    "create-ready-aircraft",
                    "AIRCRAFT_CREATE",
                    self._valid_aircraft_payload(),
                ),
            )
            self.assertTrue(created["success"], created)

            first = self._request(
                context,
                control_endpoint,
                self._message("snapshot-ready-1", "SNAPSHOT_GET", {}),
            )["payload"]
            time.sleep(0.4)
            second = self._request(
                context,
                control_endpoint,
                self._message("snapshot-ready-2", "SNAPSHOT_GET", {}),
            )["payload"]

            self.assertEqual("READY", first["engineState"])
            self.assertEqual("READY", second["engineState"])
            self.assertEqual(
                first["simulationTimeSeconds"], second["simulationTimeSeconds"]
            )
        finally:
            self._stop_runner(process)
            context.term()

    def test_runner_executes_core_instructions_in_real_bluesky(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            self._request(
                context,
                control_endpoint,
                {
                    "protocolVersion": "1.0",
                    "requestId": "create-instruction-aircraft",
                    "type": "AIRCRAFT_CREATE",
                    "exerciseGroupId": "GROUP-DEFAULT",
                    "payload": {
                        "callsign": "CCA3582",
                        "aircraftType": "A320",
                        "origin": "ZSSS",
                        "destination": "ZBAA",
                        "latitude": 31.1434,
                        "longitude": 121.8052,
                        "headingDegrees": 360,
                        "altitudeFeet": 9000,
                        "speedKnots": 250,
                        "route": ["ZSSS", "ZBAA"],
                    },
                },
            )
            self._request(
                context,
                control_endpoint,
                self._message("start-real", "START", {}),
            )
            self._request(
                context,
                control_endpoint,
                self._message(
                    "hdg-real",
                    "INSTRUCTION_EXECUTE",
                    {
                        "callsign": "CCA3582",
                        "type": "HDG",
                        "headingDegrees": 90,
                        "route": [],
                    },
                ),
            )

            deadline = time.monotonic() + 8
            heading = 360.0
            while time.monotonic() < deadline and (heading > 355 or heading < 5):
                time.sleep(0.25)
                snapshot = self._request(
                    context,
                    control_endpoint,
                    self._message("snapshot-motion", "SNAPSHOT_GET", {}),
                )
                heading = snapshot["payload"]["aircraft"][0]["headingDegrees"]
            self.assertTrue(5 < heading < 355, "heading did not change: {}".format(heading))

            route_response = self._request(
                context,
                control_endpoint,
                self._message(
                    "rte-real",
                    "INSTRUCTION_EXECUTE",
                    {
                        "callsign": "CCA3582",
                        "commandId": "rte-real-command",
                        "type": "RTE",
                        "route": ["ZBAA"],
                    },
                ),
            )
            self.assertTrue(route_response["success"], route_response)
            snapshot = self._request(
                context,
                control_endpoint,
                self._message("snapshot-route", "SNAPSHOT_GET", {}),
            )
            self.assertEqual(
                ["ZBAA"], snapshot["payload"]["aircraft"][0]["route"]
            )
            self.assertEqual(
                {
                    "commandId": "rte-real-command",
                    "activated": True,
                },
                snapshot["payload"]["aircraft"][0]["routeChange"],
            )
        finally:
            self._stop_runner(process)
            context.term()

    def test_descent_uses_explicit_vertical_speed_magnitude(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            created = self._request(
                context,
                control_endpoint,
                self._message(
                    "create-descent-aircraft",
                    "AIRCRAFT_CREATE",
                    self._valid_aircraft_payload(),
                ),
            )
            self.assertTrue(created["success"], created)
            self._request(context, control_endpoint, self._message("start-descent", "START", {}))
            command = self._request(
                context,
                control_endpoint,
                self._message(
                    "alt-descent",
                    "INSTRUCTION_EXECUTE",
                    {
                        "callsign": "CCA3582",
                        "type": "ALT",
                        "altitudeFeet": 6000,
                        "verticalSpeedFeetPerMinute": -1000,
                        "route": [],
                    },
                ),
            )
            self.assertTrue(command["success"], command)

            deadline = time.monotonic() + 8
            vertical_speed = 0.0
            while time.monotonic() < deadline:
                time.sleep(0.25)
                snapshot = self._request(
                    context,
                    control_endpoint,
                    self._message("snapshot-descent", "SNAPSHOT_GET", {}),
                )
                vertical_speed = snapshot["payload"]["aircraft"][0][
                    "verticalSpeedFeetPerMinute"
                ]
                if vertical_speed <= -900:
                    break

            self.assertLess(vertical_speed, -900)
            self.assertGreater(vertical_speed, -1100)
        finally:
            self._stop_runner(process)
            context.term()

    def test_altitude_instruction_uses_imported_height_rate_limit(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            payload = self._valid_aircraft_payload()
            payload.update({"altitudeFeet": 6000 / 0.3048, "speedKnots": 280})
            created = self._request(
                context,
                control_endpoint,
                self._message("create-perf-aircraft", "AIRCRAFT_CREATE", payload),
            )
            self.assertTrue(created["success"], created)

            command = self._request(
                context,
                control_endpoint,
                self._message(
                    "alt-imported-limit",
                    "INSTRUCTION_EXECUTE",
                    {
                        "callsign": "CCA3582",
                        "type": "ALT",
                        "altitudeFeet": 30000,
                        "verticalSpeedFeetPerMinute": 5000,
                        "route": [],
                    },
                ),
            )

            self.assertTrue(command["success"], command)
            result = command["payload"]
            self.assertTrue(result["performanceLimitApplied"])
            self.assertAlmostEqual(5000, result["requestedVerticalSpeedFeetPerMinute"])
            self.assertAlmostEqual(
                14.6304 / 0.3048 * 60,
                result["appliedVerticalSpeedFeetPerMinute"],
                delta=0.1,
            )
            self.assertEqual("simulator_backup", result["performanceSource"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_altitude_instruction_rejects_target_above_imported_ceiling(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            created = self._request(
                context,
                control_endpoint,
                self._message(
                    "create-alt-ceiling-aircraft",
                    "AIRCRAFT_CREATE",
                    self._valid_aircraft_payload(),
                ),
            )
            self.assertTrue(created["success"], created)

            command = self._request(
                context,
                control_endpoint,
                self._message(
                    "alt-above-imported-ceiling",
                    "INSTRUCTION_EXECUTE",
                    {
                        "callsign": "CCA3582",
                        "type": "ALT",
                        "altitudeFeet": 42000,
                        "route": [],
                    },
                ),
            )

            self.assertFalse(command["success"], command)
            self.assertIn("升限", command["message"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_speed_instruction_rejects_value_outside_imported_envelope(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            payload = self._valid_aircraft_payload()
            payload.update({"altitudeFeet": 6000 / 0.3048, "speedKnots": 280})
            created = self._request(
                context,
                control_endpoint,
                self._message("create-speed-envelope", "AIRCRAFT_CREATE", payload),
            )
            self.assertTrue(created["success"], created)

            rejected = self._request(
                context,
                control_endpoint,
                self._message(
                    "spd-outside-envelope",
                    "INSTRUCTION_EXECUTE",
                    {
                        "callsign": "CCA3582",
                        "type": "SPD",
                        "speedKnots": 350,
                        "route": [],
                    },
                ),
            )

            self.assertFalse(rejected["success"], rejected)
            self.assertEqual("ENGINE_REJECTED", rejected["code"])
            self.assertIn("速度包线", rejected["message"])
            self.assertIn("196", rejected["message"])
            self.assertIn("310", rejected["message"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_snapshot_exposes_current_imported_airborne_envelope(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            payload = self._valid_aircraft_payload()
            payload.update({"altitudeFeet": 6000 / 0.3048, "speedKnots": 280})
            created = self._request(
                context,
                control_endpoint,
                self._message("create-envelope-snapshot", "AIRCRAFT_CREATE", payload),
            )

            self.assertTrue(created["success"], created)
            envelope = created["payload"]["performanceEnvelope"]
            self.assertEqual("simulator_backup", envelope["sourceDatabase"])
            self.assertEqual("CRUISE", envelope["phase"])
            self.assertAlmostEqual(196.3, envelope["minimumCasKnots"], delta=0.1)
            self.assertAlmostEqual(310.0, envelope["maximumCasKnots"], delta=0.1)
            self.assertAlmostEqual(2880.0, envelope["maximumClimbFeetPerMinute"], delta=0.1)
            self.assertAlmostEqual(3500.0, envelope["maximumDescentFeetPerMinute"], delta=0.1)
        finally:
            self._stop_runner(process)
            context.term()

    def test_reset_clears_aircraft_clock_and_returns_ready(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            created = self._request(
                context,
                control_endpoint,
                self._message("create-before-reset", "AIRCRAFT_CREATE", self._valid_aircraft_payload()),
            )
            self.assertTrue(created["success"], created)
            route_change = self._request(
                context,
                control_endpoint,
                self._message(
                    "route-before-reset",
                    "INSTRUCTION_EXECUTE",
                    {
                        "callsign": "CCA3582",
                        "commandId": "stale-route-command",
                        "type": "RTE",
                        "route": ["ZBAA"],
                    },
                ),
            )
            self.assertTrue(route_change["success"], route_change)
            self._request(context, control_endpoint, self._message("start-before-reset", "START", {}))
            time.sleep(0.3)

            reset = self._request(
                context,
                control_endpoint,
                self._message("reset-real", "RESET", {}),
            )
            snapshot = self._request(
                context,
                control_endpoint,
                self._message("snapshot-after-reset", "SNAPSHOT_GET", {}),
            )["payload"]

            self.assertTrue(reset["success"], reset)
            self.assertEqual("READY", reset["payload"]["engineState"])
            self.assertEqual("READY", snapshot["engineState"])
            self.assertEqual(0.0, snapshot["simulationTimeSeconds"])
            self.assertEqual([], snapshot["aircraft"])

            recreated = self._request(
                context,
                control_endpoint,
                self._message(
                    "recreate-after-reset",
                    "AIRCRAFT_CREATE",
                    self._valid_aircraft_payload(),
                ),
            )
            self.assertTrue(recreated["success"], recreated)
            self.assertNotIn("routeChange", recreated["payload"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_reference_search_reads_real_bluesky_data(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            searches = {
                "AIRPORT": ("ZSSS", "ZSSS"),
                "WAYPOINT": ("CEN", "CEN"),
                "AIRCRAFT_TYPE": ("A32", "A320"),
            }
            for kind, (query, expected) in searches.items():
                response = self._request(
                    context,
                    control_endpoint,
                    self._message(
                        "reference-{}".format(kind.lower()),
                        "REFERENCE_SEARCH",
                        {"kind": kind, "query": query, "limit": 20},
                    ),
                )
                self.assertTrue(response["success"], response)
                self.assertIn(
                    expected,
                    [item["code"] for item in response["payload"]["items"]],
                )
        finally:
            self._stop_runner(process)
            context.term()

    def test_aircraft_type_reference_marks_imported_performance_types(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            response = self._request(
                context,
                control_endpoint,
                self._message(
                    "reference-imported-perf",
                    "REFERENCE_SEARCH",
                    {"kind": "AIRCRAFT_TYPE", "query": "A31", "limit": 20},
                ),
            )
            items = {item["code"]: item for item in response["payload"]["items"]}

            self.assertTrue(response["success"], response)
            self.assertTrue(items["A319"]["airbornePerformanceAvailable"])
            self.assertEqual("A319", items["A319"]["performanceSourceAircraftType"])
            self.assertFalse(items["A318"]["airbornePerformanceAvailable"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_runner_rejects_unknown_type_and_airport_without_leaving_aircraft(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            base = {
                "callsign": "BAD0001", "aircraftType": "XXXX",
                "origin": "ZSSS", "destination": "ZBAA",
                "latitude": 31.1434, "longitude": 121.8052,
                "headingDegrees": 360, "altitudeFeet": 9000,
                "speedKnots": 250, "route": ["ZBAA"],
            }
            unknown_type = self._request(
                context, control_endpoint,
                self._message("unknown-type", "AIRCRAFT_CREATE", base),
            )
            self.assertFalse(unknown_type["success"])

            base.update({"callsign": "BAD0002", "aircraftType": "A320", "origin": "XXXX"})
            unknown_airport = self._request(
                context, control_endpoint,
                self._message("unknown-airport", "AIRCRAFT_CREATE", base),
            )
            self.assertFalse(unknown_airport["success"])

            snapshot = self._request(
                context, control_endpoint,
                self._message("snapshot-after-invalid", "SNAPSHOT_GET", {}),
            )
            self.assertEqual([], snapshot["payload"]["aircraft"])
        finally:
            self._stop_runner(process)
            context.term()

    def test_runner_rejects_rte_whose_last_point_is_not_destination(self):
        control_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        state_endpoint = "tcp://127.0.0.1:{}".format(free_tcp_port())
        process = self._start_runner(control_endpoint, state_endpoint)
        context = zmq.Context()
        try:
            self._wait_for_ping(context, control_endpoint, process)
            created = self._request(
                context, control_endpoint,
                self._message("create-rte-aircraft", "AIRCRAFT_CREATE", {
                    "callsign": "CCA3582", "aircraftType": "A320",
                    "origin": "ZSSS", "destination": "ZBAA",
                    "latitude": 31.1434, "longitude": 121.8052,
                    "headingDegrees": 360, "altitudeFeet": 9000,
                    "speedKnots": 250, "route": ["ZSSS", "ZBAA"],
                }),
            )
            self.assertTrue(created["success"], created)
            rejected = self._request(
                context, control_endpoint,
                self._message("invalid-rte-destination", "INSTRUCTION_EXECUTE", {
                    "callsign": "CCA3582", "type": "RTE", "route": ["ZSSS"],
                }),
            )
            self.assertFalse(rejected["success"])
            snapshot = self._request(
                context, control_endpoint,
                self._message("snapshot-after-rte", "SNAPSHOT_GET", {}),
            )
            self.assertEqual(["ZSSS", "ZBAA"], snapshot["payload"]["aircraft"][0]["route"])
        finally:
            self._stop_runner(process)
            context.term()

    def _message(self, request_id, message_type, payload):
        return {
            "protocolVersion": "1.0",
            "requestId": request_id,
            "type": message_type,
            "exerciseGroupId": "GROUP-DEFAULT",
            "payload": payload,
        }

    def _valid_aircraft_payload(self):
        return {
            "callsign": "CCA3582",
            "aircraftType": "A320",
            "origin": "ZSSS",
            "destination": "ZBAA",
            "latitude": 31.1434,
            "longitude": 121.8052,
            "headingDegrees": 360,
            "altitudeFeet": 9000,
            "speedKnots": 250,
            "route": ["ZSSS", "ZBAA"],
        }

    def _start_runner(self, control_endpoint, state_endpoint):
        return subprocess.Popen(
            [
                sys.executable,
                "-m",
                "bluesky.plugins.training_adapter.runner",
                "--control-endpoint",
                control_endpoint,
                "--state-endpoint",
                state_endpoint,
                "--workdir",
                str(REPOSITORY_ROOT),
            ],
            cwd=str(REPOSITORY_ROOT),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def _stop_runner(self, process):
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    def _request(self, context, endpoint, payload):
        socket_ = context.socket(zmq.REQ)
        socket_.linger = 0
        socket_.rcvtimeo = 3000
        socket_.sndtimeo = 3000
        socket_.connect(endpoint)
        try:
            socket_.send_json(payload)
            return json.loads(socket_.recv().decode("utf-8"))
        finally:
            socket_.close()

    def _wait_for_ping(self, context, endpoint, process):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if process.poll() is not None:
                self.fail("Adapter runner exited before answering PING")
            socket_ = context.socket(zmq.REQ)
            socket_.linger = 0
            socket_.rcvtimeo = 500
            socket_.sndtimeo = 500
            socket_.connect(endpoint)
            try:
                socket_.send_json(
                    {
                        "protocolVersion": "1.0",
                        "requestId": "runner-ping",
                        "type": "PING",
                        "exerciseGroupId": "GROUP-DEFAULT",
                        "payload": {},
                    }
                )
                return json.loads(socket_.recv().decode("utf-8"))
            except zmq.Again:
                time.sleep(0.25)
            finally:
                socket_.close()
        self.fail("Adapter runner did not answer PING within 30 seconds")


if __name__ == "__main__":
    unittest.main()
