# -*- coding: utf-8 -*-
"""P04：Python 侧 Protocol 2.0 信封校验（与 Java 共享同一份向量，详细设计 10.1）。"""
import json
import pathlib
import unittest

from bluesky.plugins.training_adapter.protocol_v2 import ProtocolEnvelope

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPOSITORY_ROOT / "docs" / "contracts" / "adapter-protocol-v2.schema.json"
VECTORS_PATH = REPOSITORY_ROOT / "docs" / "contracts" / "vectors" / "adapter-protocol-v2-vectors.json"


class ProtocolV2EnvelopeTest(unittest.TestCase):
    def setUp(self):
        with VECTORS_PATH.open("r", encoding="utf-8") as handle:
            self.vectors = json.load(handle)["vectors"]
        self.envelope = ProtocolEnvelope(schema_path=str(SCHEMA_PATH))

    def test_shared_vectors_validate_consistently_with_java(self):
        self.assertTrue(self.vectors, "共享向量不能为空")
        for vector in self.vectors:
            problems = self.envelope.validate(
                vector["envelope"],
                correlates_to_request_id=vector.get("correlatesToRequestId"))
            expect_valid = bool(vector["expectValid"])
            self.assertEqual(
                expect_valid,
                not problems,
                "%s 校验结论与预期不一致: %s" % (vector["name"], problems))

    def test_response_correlation_must_match_paired_request(self):
        for vector in self.vectors:
            correlates_to = vector.get("correlatesToRequestId")
            if correlates_to is None:
                continue
            problems = self.envelope.validate(
                vector["envelope"], correlates_to_request_id=correlates_to)
            self.assertEqual(
                bool(vector["expectValid"]), not problems,
                "%s 关联校验结论与预期不一致: %s" % (vector["name"], problems))

    def test_from_dict_to_dict_round_trip_keeps_fields(self):
        source = {
            "protocolVersion": "2.0",
            "messageKind": "REQUEST",
            "messageType": "HELLO",
            "exerciseGroupId": "group-1",
            "engineInstanceId": "engine-1",
            "requestId": "req-1",
            "correlationRequestId": None,
            "idempotencyKey": None,
            "sequence": 1,
            "systemTimeUtc": "2026-08-31T09:00:00.000Z",
            "simulationTimeSeconds": 0.0,
            "payloadSchemaVersion": None,
            "payload": {},
        }
        envelope = ProtocolEnvelope.from_dict(dict(source), schema_path=str(SCHEMA_PATH))
        self.assertEqual([], envelope.validate())
        round_trip = envelope.to_dict()
        for key, value in source.items():
            self.assertEqual(value, round_trip.get(key), key)

    def test_missing_required_field_is_rejected(self):
        broken = {
            "protocolVersion": "2.0",
            "messageKind": "REQUEST",
            "exerciseGroupId": "group-1",
        }
        problems = ProtocolEnvelope.from_dict(
            broken, schema_path=str(SCHEMA_PATH)).validate()
        self.assertTrue(any("messageType" in problem for problem in problems))

    def test_invalid_utc_and_boolean_simulation_time_are_rejected(self):
        source = dict(self.vectors[0]["envelope"])
        source["systemTimeUtc"] = "2026-08-31 09:00:00"
        source["simulationTimeSeconds"] = True

        problems = self.envelope.validate(source)

        self.assertTrue(any("systemTimeUtc" in problem for problem in problems))
        self.assertTrue(any("simulationTimeSeconds" in problem for problem in problems))


if __name__ == "__main__":
    unittest.main()
