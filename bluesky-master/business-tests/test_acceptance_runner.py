from __future__ import annotations

import importlib.util
import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("acceptance_runner.py")
SPEC = importlib.util.spec_from_file_location("acceptance_runner", MODULE_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = RUNNER
SPEC.loader.exec_module(RUNNER)


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        payload = json.dumps({
            "id": "instruction-1",
            "status": "COMPLETED",
            "operationId": "operation-1",
            "echo": body,
        }).encode("utf-8")
        self.send_response(202)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Request-Id", self.headers.get("X-Request-Id", "missing"))
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format, *_args):
        return


class AcceptanceRunnerUnitTest(unittest.TestCase):
    def test_all_design_scenarios_have_stable_entry(self):
        self.assertEqual([f"AT-{number:02d}" for number in range(1, 13)],
                         list(RUNNER.SCENARIOS))
        expected = {
            "AT-01": "runCompleteFlight",
            "AT-02": "runMissedApproach",
            "AT-03": "runConcurrentHandover",
            "AT-04": "runAtomicRouteScenario",
            "AT-05": "runHoldAndOrbit",
            "AT-06": "runTransponderMatrix",
            "AT-07": "runBothTargetKinds",
            "AT-08": "runProfileLifecycleAndCommands",
            "AT-09": "runReportsScriptsMessages",
            "AT-10": "runDisplayProfileScenario",
            "AT-11": "runFailureInjectionMatrix",
            "AT-12": "runEightHourProfile",
        }
        for scenario_id, method in expected.items():
            self.assertTrue(hasattr(RUNNER.SCENARIOS[scenario_id], method))

    def test_report_never_converts_blocked_or_failed_to_pass(self):
        recorder = RUNNER.EvidenceRecorder("run", 7)
        blocked = RUNNER.ScenarioResult("AT-01", "完整飞行", RUNNER.BLOCKED)
        failed = RUNNER.ScenarioResult("AT-02", "复飞", RUNNER.FAIL)
        report = RUNNER.build_report(recorder, {"baseUrl": "http://test"}, [blocked, failed])
        self.assertEqual(RUNNER.FAIL, report["overallStatus"])
        report = RUNNER.build_report(recorder, {"baseUrl": "http://test"}, [blocked])
        self.assertEqual(RUNNER.BLOCKED, report["overallStatus"])

    def test_capacity_gate_checks_sample_cpu_and_growth_thresholds(self):
        passing = {
            "warmupCompleted": True,
            "steadyWindowCompleted": True,
            "eightHourCompleted": True,
            "noProcessRestart": True,
            "noOom": True,
            "faultScheduleCompleted": True,
            "reliableEventSideEffectsExactlyOnce": True,
            "successSamples": 10000,
            "parallelGroups": 8,
            "aircraftPerGroup": 200,
            "terminalsPerGroup": 8,
            "sseClients": 64,
            "getRequestsPerSecond": 100,
            "writeRequestsPerSecond": 20,
            "fullStateGroupsAtOneHz": 8,
            "writeMixPct": {"HDG": 40, "ALT": 20, "SPD": 20,
                            "RTE": 10, "HANDOVER": 10},
            "restSuccessP95Ms": 499.9,
            "adapterAckP95Ms": 199.9,
            "sseEndToEndP95Ms": 999.9,
            "business5xxRate": 0.0009,
            "javaPeakHeapGiB": 6,
            "adapterPerGroupPeakRssGiB": 2,
            "archiveBytesPerAircraftFrame": 150,
            "serverCpuFiveMinuteAverageMaxPct": 70,
            "serverCpuAnyContinuousFiveMinutesMaxPct": 85,
            "javaHeapGrowthPct": 10,
            "adapterRssGrowthPct": 10,
            "sseQueueGrowthPct": 10,
            "dbConnectionGrowthPct": 10,
            "browserDisconnectCount": 20,
            "adapterReconnectCount": 8,
            "javaControlledRestartCount": 2,
        }
        RUNNER.CapacityAcceptanceRunner._assert_summary(passing)
        failing = dict(passing, javaHeapGrowthPct=10.01)
        with self.assertRaises(RUNNER.AcceptanceFailure):
            RUNNER.CapacityAcceptanceRunner._assert_summary(failing)

    def test_http_evidence_redacts_secret_and_collects_ids(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            recorder = RUNNER.EvidenceRecorder("run", 7)
            client = RUNNER.ApiClient(f"http://127.0.0.1:{server.server_port}",
                                      "gateway-secret", recorder)
            identity = RUNNER.Identity("OPERATIONS", "ops")
            result = client.request("POST", "/test", identity,
                                    {"password": "do-not-record", "value": 1},
                                    expected=(202,))
            self.assertEqual(202, result.status)
            self.assertIn("operation-1", recorder.operation_ids)
            self.assertTrue(recorder.request_ids[0].startswith("bat-run-"))
            self.assertEqual("<redacted>", recorder.http_calls[0]["requestBody"]["password"])
            serialized = json.dumps(recorder.http_calls)
            self.assertNotIn("gateway-secret", serialized)
            self.assertNotIn("do-not-record", serialized)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
