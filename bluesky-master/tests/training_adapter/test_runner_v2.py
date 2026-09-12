# -*- coding: utf-8 -*-
"""P04：runner_v2.AdapterRuntime 集成测试（ROUTER/PUB 真实 socket，详细设计 10.1）。"""
import json
import pathlib
import socket
import threading
import time
import unittest

import zmq

from bluesky.plugins.training_adapter.protocol_v2 import ProtocolEnvelope
from bluesky.plugins.training_adapter.runner_v2 import AdapterRuntime

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPOSITORY_ROOT / "docs" / "contracts" / "adapter-protocol-v2.schema.json"


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def request_envelope(message_type, request_id, idempotency_key=None, sequence=1):
    return {
        "protocolVersion": "2.0",
        "messageKind": "REQUEST",
        "messageType": message_type,
        "exerciseGroupId": "group-1",
        "engineInstanceId": "engine-1",
        "requestId": request_id,
        "correlationRequestId": None,
        "idempotencyKey": idempotency_key,
        "sequence": sequence,
        "systemTimeUtc": "2026-08-31T09:00:00.000Z",
        "simulationTimeSeconds": 0.0,
        "payloadSchemaVersion": None,
        "payload": {},
    }


class AdapterRuntimeTest(unittest.TestCase):
    def test_failed_native_execution_is_cached_and_rejected_not_applied(self):
        count=[]
        def failing(_):
            count.append(1)
            raise ValueError("out of performance envelope")
        self.runtime.handlers["INSTRUCTION_APPLY"]=failing
        request=request_envelope("INSTRUCTION_APPLY","reject-once","stable-reject")
        first=json.loads(self.runtime._handle_request(json.dumps(request).encode()))
        request['sequence']=2;request['requestId']='reject-replay'
        second=json.loads(self.runtime._handle_request(json.dumps(request).encode()))
        self.assertEqual("INSTRUCTION_REJECTED",first['messageType'])
        self.assertFalse(first['payload']['accepted'])
        self.assertTrue(second['payload']['replayed'])
        self.assertEqual(1,len(count))

    def setUp(self):
        self.calls = []
        handlers = {
            "HELLO": self._hello_handler,
            "INSTRUCTION_STATUS_GET": lambda payload: {"state": "EXECUTING"},
            "INSTRUCTION_APPLY": lambda payload: {
                "accepted": False, "code": "INVALID_INSTRUCTION",
                "message": "rejected"},
            "AIRCRAFT_APPLY": lambda payload: self._reject_engine(payload),
        }
        self.runtime = AdapterRuntime(
            handlers,
            control_endpoint="tcp://127.0.0.1:%d" % free_port(),
            state_endpoint="tcp://127.0.0.1:%d" % free_port(),
            exercise_group_id="group-1",
            engine_instance_id="engine-1",
            schema_path=str(SCHEMA_PATH))
        self.thread = threading.Thread(target=self.runtime.run_control_loop,
                                       name="adapter-runtime-v2", daemon=True)
        self.thread.start()
        self.context = zmq.Context()
        time.sleep(0.3)

    def _hello_handler(self, payload):
        self.calls.append(payload)
        return {"connected": True}

    @staticmethod
    def _reject_engine(_payload):
        raise ValueError("性能超限")

    def tearDown(self):
        self.runtime.shutdown()
        self.thread.join(timeout=5)
        self.context.term()

    def _dealer(self):
        dealer = self.context.socket(zmq.DEALER)
        dealer.setsockopt(zmq.LINGER, 0)
        dealer.setsockopt(zmq.RCVTIMEO, 5000)
        dealer.connect(self.runtime.control_endpoint)
        return dealer

    def test_hello_handshake_answers_with_correlation(self):
        dealer = self._dealer()
        try:
            dealer.send(json.dumps(
                request_envelope("HELLO", "req-1")).encode("utf-8"))
            reply = json.loads(dealer.recv_multipart()[-1])
        finally:
            dealer.close()

        self.assertEqual("RESPONSE", reply["messageKind"])
        self.assertEqual("HELLO_ACK", reply["messageType"])
        self.assertEqual("req-1", reply["correlationRequestId"])
        self.assertEqual("2.0", reply["protocolVersion"])
        self.assertTrue(reply["payload"]["connected"])

    def test_state_events_start_at_sequence_one_and_increment(self):
        subscriber = self.context.socket(zmq.SUB)
        subscriber.setsockopt(zmq.LINGER, 0)
        subscriber.setsockopt(zmq.RCVTIMEO, 5000)
        subscriber.setsockopt(zmq.SUBSCRIBE, b"")
        subscriber.connect(self.runtime.state_endpoint)
        time.sleep(0.3)

        self.runtime.publish_state({"position": "fixed"})
        first = json.loads(self._recv_with_retry(subscriber))
        self.runtime.publish_state({"position": "moved"})
        second = json.loads(self._recv_with_retry(subscriber))
        subscriber.close()

        self.assertEqual("EVENT", first["messageKind"])
        self.assertTrue(first["sequence"] < second["sequence"],
                        "PUB 帧序号必须递增: %s -> %s" % (first["sequence"], second["sequence"]))

    def test_control_response_and_state_event_have_independent_sequences(self):
        subscriber = self.context.socket(zmq.SUB)
        subscriber.setsockopt(zmq.LINGER, 0)
        subscriber.setsockopt(zmq.RCVTIMEO, 5000)
        subscriber.setsockopt(zmq.SUBSCRIBE, b"")
        subscriber.connect(self.runtime.state_endpoint)
        time.sleep(0.3)
        dealer = self._dealer()
        try:
            dealer.send(json.dumps(request_envelope(
                "HELLO", "req-channel-sequence")).encode("utf-8"))
            response = json.loads(dealer.recv())
            self.runtime.publish_state({"position": "fixed"})
            event = json.loads(self._recv_with_retry(subscriber))
        finally:
            dealer.close()
            subscriber.close()

        self.assertEqual(1, response["sequence"])
        self.assertEqual(1, event["sequence"])

    def _recv_with_retry(self, subscriber):
        """PUB/SUB 慢加入者：偶发丢首帧时由发送侧重发，测试只取先到的一帧语义。"""
        for _ in range(5):
            try:
                return subscriber.recv()
            except zmq.Again:
                self.runtime.publish_heartbeat()
        self.fail("5 秒内未收到任何状态帧")

    def test_same_idempotency_key_replays_without_reinvoking_handler(self):
        dealer = self._dealer()
        try:
            dealer.send(json.dumps(request_envelope(
                "HELLO", "req-a", idempotency_key="key-1", sequence=1)).encode("utf-8"))
            first = json.loads(dealer.recv())
            dealer.send(json.dumps(request_envelope(
                "HELLO", "req-b", idempotency_key="key-1", sequence=2)).encode("utf-8"))
            second = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertFalse(first["payload"]["replayed"])
        self.assertTrue(second["payload"]["replayed"])
        self.assertEqual(1, len(self.calls), "同键重放不得重复执行 handler")

    def test_stale_protocol_version_is_rejected_with_error_response(self):
        dealer = self._dealer()
        try:
            stale = request_envelope("HELLO", "req-stale")
            stale["protocolVersion"] = "1.0"
            dealer.send(json.dumps(stale).encode("utf-8"))
            reply = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertEqual("RESPONSE", reply["messageKind"])
        self.assertFalse(reply["payload"]["accepted"])
        self.assertEqual("PROTOCOL_VERSION_MISMATCH", reply["payload"]["code"])
        self.assertEqual("req-stale", reply["correlationRequestId"])

    def test_oversized_frame_is_rejected_before_json_parsing(self):
        reply = json.loads(self.runtime._handle_request(b"{" + b"x" * (1024 * 1024)))

        self.assertFalse(reply["payload"]["accepted"])
        self.assertEqual("FRAME_TOO_LARGE", reply["payload"]["code"])

    def test_wrong_group_or_engine_is_rejected_without_invoking_handler(self):
        dealer = self._dealer()
        try:
            wrong_group = request_envelope("HELLO", "req-wrong-group", sequence=1)
            wrong_group["exerciseGroupId"] = "group-other"
            dealer.send(json.dumps(wrong_group).encode("utf-8"))
            group_reply = json.loads(dealer.recv())

            wrong_engine = request_envelope("HELLO", "req-wrong-engine", sequence=2)
            wrong_engine["engineInstanceId"] = "engine-old"
            dealer.send(json.dumps(wrong_engine).encode("utf-8"))
            engine_reply = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertEqual("EXERCISE_GROUP_MISMATCH", group_reply["payload"]["code"])
        self.assertEqual("ENGINE_INSTANCE_MISMATCH", engine_reply["payload"]["code"])
        self.assertEqual([], self.calls)

    def test_rejection_uses_response_type_matching_the_request(self):
        dealer = self._dealer()
        try:
            request = request_envelope(
                "INSTRUCTION_STATUS_GET", "req-wrong-route", sequence=1)
            request["exerciseGroupId"] = "group-other"
            dealer.send(json.dumps(request).encode("utf-8"))
            reply = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertEqual("INSTRUCTION_STATUS_RESULT", reply["messageType"])
        self.assertEqual("EXERCISE_GROUP_MISMATCH", reply["payload"]["code"])

    def test_instruction_business_rejection_uses_rejected_message_type(self):
        dealer = self._dealer()
        try:
            request = request_envelope(
                "INSTRUCTION_APPLY", "req-rejected", sequence=1)
            dealer.send(json.dumps(request).encode("utf-8"))
            reply = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertEqual("INSTRUCTION_REJECTED", reply["messageType"])
        self.assertFalse(reply["payload"]["accepted"])

    def test_engine_validation_error_returns_rejection_without_killing_runtime(self):
        dealer = self._dealer()
        try:
            dealer.send(json.dumps(request_envelope(
                "AIRCRAFT_APPLY", "req-engine-reject", sequence=1)).encode("utf-8"))
            rejected = json.loads(dealer.recv())
            dealer.send(json.dumps(request_envelope(
                "HELLO", "req-after-reject", sequence=2)).encode("utf-8"))
            healthy = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertEqual("AIRCRAFT_APPLIED", rejected["messageType"])
        self.assertFalse(rejected["payload"]["accepted"])
        self.assertEqual("ENGINE_REJECTED", rejected["payload"]["code"])
        self.assertTrue(healthy["payload"]["accepted"])

    def test_gap_and_unknown_duplicate_are_rejected_without_side_effects(self):
        dealer = self._dealer()
        try:
            first = request_envelope("HELLO", "req-first", sequence=1)
            dealer.send(json.dumps(first).encode("utf-8"))
            dealer.recv()

            gap = request_envelope("HELLO", "req-gap", sequence=3)
            dealer.send(json.dumps(gap).encode("utf-8"))
            gap_reply = json.loads(dealer.recv())

            duplicate = request_envelope("HELLO", "req-unknown-duplicate", sequence=1)
            dealer.send(json.dumps(duplicate).encode("utf-8"))
            duplicate_reply = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertEqual("SEQUENCE_GAP", gap_reply["payload"]["code"])
        self.assertEqual("SEQUENCE_DUPLICATE", duplicate_reply["payload"]["code"])
        self.assertEqual([{}], self.calls)

    def test_gap_recovers_on_next_request_instead_of_deadlocking(self):
        """评审 A3：缺口上报一次后必须自愈，通道不得永久死锁。"""
        dealer = self._dealer()
        try:
            dealer.send(json.dumps(request_envelope(
                "HELLO", "req-gap-1", sequence=1)).encode("utf-8"))
            dealer.recv()

            dealer.send(json.dumps(request_envelope(
                "HELLO", "req-gap-3", sequence=3)).encode("utf-8"))
            gap_reply = json.loads(dealer.recv())
            self.assertEqual("SEQUENCE_GAP", gap_reply["payload"]["code"])

            dealer.send(json.dumps(request_envelope(
                "HELLO", "req-gap-4", sequence=4)).encode("utf-8"))
            recovered = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertTrue(recovered["payload"]["accepted"],
                        "缺口后的下一个请求必须重新同步成功")
        self.assertFalse(self.runtime.out_of_sync)

    def test_rejected_route_consumes_sequence_so_channel_survives(self):
        """评审 A3 核心回归：组/实例不匹配被拒的请求，其序号也必须消费；
        旧实现不消费导致其后所有合法请求命中 GAP，通道永久死锁。"""
        dealer = self._dealer()
        try:
            wrong_group = request_envelope("HELLO", "req-route-1", sequence=1)
            wrong_group["exerciseGroupId"] = "group-other"
            dealer.send(json.dumps(wrong_group).encode("utf-8"))
            dealer.recv()

            dealer.send(json.dumps(request_envelope(
                "HELLO", "req-route-2", sequence=2)).encode("utf-8"))
            healthy = json.loads(dealer.recv())
        finally:
            dealer.close()

        self.assertTrue(healthy["payload"]["accepted"],
                        "被拒请求之后的顺序请求必须被接受（序号已消费）")

    def test_non_object_json_frame_returns_error_response(self):
        """评审 A1：合法 JSON 但非对象的帧（123/"abc"/[1,2]）不得杀死进程。"""
        for bad_frame in (b"123", b'"abc"', b"[1,2]"):
            reply = json.loads(self.runtime._handle_request(bad_frame))
            self.assertFalse(reply["payload"]["accepted"], bad_frame)
            self.assertEqual("ENVELOPE_INVALID", reply["payload"]["code"], bad_frame)

    def test_multipart_request_frame_is_answered_not_crashed(self):
        """评审 A2：DEALER 发 multipart 时 ROUTER 不假定恰好两帧。"""
        dealer = self._dealer()
        try:
            envelope = json.dumps(request_envelope("HELLO", "req-multi")).encode("utf-8")
            dealer.send_multipart([b"extra-part", envelope])
            frames = dealer.recv_multipart()
            self.assertEqual(b"extra-part", frames[0])
            reply = json.loads(frames[-1])
        finally:
            dealer.close()

        self.assertEqual("HELLO_ACK", reply["messageType"])
        self.assertEqual("req-multi", reply["correlationRequestId"])


if __name__ == "__main__":
    unittest.main()
