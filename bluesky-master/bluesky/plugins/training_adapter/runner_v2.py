# -*- coding: utf-8 -*-
"""P04：Adapter Runtime 2.0（详细设计 2.2 第 10 节）。

每训练组一个 ROUTER control socket + 一个 PUB state socket：
- run_control_loop: 校验 2.0 信封、序号去重/缺口标记、幂等缓存执行、
  按关联 requestId 回复 RESPONSE（复制 idempotencyKey）。
- publish_state / publish_heartbeat: PUB 通道 EVENT 帧，序号从 1 递增。
"""
import json
import argparse
import threading
import time
import uuid
import hashlib
import signal
from pathlib import Path

import zmq

from .protocol_v2 import (
    IdempotencyResultCache,
    MAX_FRAME_BYTES,
    ProtocolEnvelope,
    ProtocolError,
    SequenceTracker,
)


class AdapterRuntime(object):
    """ROUTER/PUB 事件循环；handlers: messageType -> callable(payload) -> payload。"""

    def __init__(self, handlers, control_endpoint, state_endpoint,
                 exercise_group_id="group-1", engine_instance_id=None,
                 schema_path=None, now_ms=None, tick=None, health_supplier=None,
                 simulation_time_supplier=None, state_supplier=None):
        self.handlers = dict(handlers)
        self.control_endpoint = control_endpoint
        self.state_endpoint = state_endpoint
        self.exercise_group_id = exercise_group_id
        self.engine_instance_id = engine_instance_id or ("engine-" + uuid.uuid4().hex[:12])
        self.schema_path = schema_path
        self._stopped = threading.Event()
        # control 与 state 使用独立 socket；跨 socket 不保证到达顺序，因此分别计序。
        # control tracker 的 inbound/outbound 分别对应请求/响应方向。
        self._control_sequence = SequenceTracker()
        self._state_sequence = SequenceTracker()
        self._cache = IdempotencyResultCache(now_ms=now_ms)
        self._context = None
        self._state_socket = None
        self._state_lock = threading.Lock()
        self._tick = tick
        self._health_supplier = health_supplier
        # 事件/响应的 simulationTimeSeconds 取引擎当前值而非硬编码 0.0（评审 A11）
        self._simulation_time_supplier = simulation_time_supplier or (lambda: 0.0)
        self._state_supplier = state_supplier

    def run_control_loop(self):
        if self._context is None:
            self._context = zmq.Context()
        self._ensure_state_socket()
        control = self._context.socket(zmq.ROUTER)
        control.setsockopt(zmq.LINGER, 0)
        control.bind(self.control_endpoint)
        poller = zmq.Poller()
        poller.register(control, zmq.POLLIN)
        next_heartbeat = time.monotonic()
        next_purge = time.monotonic() + 60.0
        next_state = time.monotonic()
        try:
            while not self._stopped.is_set():
                # poll(0) 非阻塞：Windows 上 libzmq 的 poll(>0) 实测占用约
                # 15.6ms 粗粒度定时器，会限制 tick 驱动频率；节流交给
                # engine.update() 内部的高精度 sleep
                if poller.poll(0):
                    # ROUTER 收到 multipart 时不假定恰好两帧（评审 A2）：
                    # 首帧为路由 identity，末帧为载荷
                    frames = control.recv_multipart()
                    identity = frames[0]
                    payload = frames[-1] if len(frames) > 1 else frames[0]
                    reply = self._safe_handle(payload)
                    control.send_multipart(frames[:-1] + [reply])
                    parsed_reply = json.loads(reply)
                    if parsed_reply.get("messageType") == "STOPPED" and parsed_reply.get("payload", {}).get("accepted"):
                        self.shutdown()
                if self._tick is not None:
                    self._safe_tick()
                now = time.monotonic()
                if self._state_supplier is not None and now >= next_state:
                    try:
                        self.publish_snapshot(self._state_supplier())
                    except Exception as failure:
                        import sys
                        print("adapter state failed: %s" % failure, file=sys.stderr)
                    next_state = now + 0.2
                if self._health_supplier is not None and now >= next_heartbeat:
                    self._publish_event("HEALTH", self._health_supplier())
                    next_heartbeat = now + 1.0
                if now >= next_purge:
                    # 幂等缓存过期清理必须被调度，否则只增不减（评审 A7）
                    self._cache.purge_expired()
                    next_purge = now + 60.0
        finally:
            control.close()
            if self._state_socket is not None:
                with self._state_lock:
                    if self._state_socket is not None:
                        self._state_socket.close()
                        self._state_socket = None
            if self._context is not None:
                self._context.term()

    def _safe_handle(self, payload):
        """control 循环边界：单条坏请求的任何异常都转为错误响应，不得杀死循环（评审 A4）。"""
        try:
            return self._handle_request(payload)
        except ProtocolError as failure:
            return self._error_response(None, None, None, failure.code, str(failure))
        except Exception as failure:  # pragma: no cover - 防御边界
            return self._error_response(
                None, None, None, "ENGINE_ERROR", "adapter 内部错误: %s" % failure)

    def _safe_tick(self):
        """引擎 step 异常不杀死 adapter（评审 A6）；v1 同样按单步隔离。"""
        try:
            self._tick()
        except Exception as failure:  # engine boundary
            import sys
            print("adapter tick failed: %s" % failure, file=sys.stderr)

    def publish_state(self, payload, message_type="STATE_SNAPSHOT_CHUNK"):
        self._publish_event(message_type, payload)

    def publish_snapshot(self, snapshot):
        aircraft = snapshot.get("aircraft") or []
        pieces = []
        for offset in range(0, max(1, len(aircraft)), 100):
            part = dict(snapshot, aircraft=aircraft[offset:offset + 100])
            pieces.append(json.dumps(part, ensure_ascii=False, separators=(",", ":")))
        checksum = hashlib.sha256(("[" + ",".join(pieces) + "]").encode("utf-8")).hexdigest()
        snapshot_id = uuid.uuid4().hex
        for index, payload in enumerate(pieces):
            self.publish_state({"snapshotId": snapshot_id, "chunkIndex": index,
                                "chunkCount": len(pieces), "checksum": checksum,
                                "payload": payload})

    def publish_heartbeat(self):
        self._publish_event("HEALTH", {"sentAt": time.time()})

    def shutdown(self):
        self._stopped.set()

    # ------------------------------------------------------------------ internal

    def _handle_request(self, payload):
        if payload is None or len(payload) > MAX_FRAME_BYTES:
            return self._error_response(
                None, None, None, "FRAME_TOO_LARGE", "帧超过 1 MiB 上限")
        try:
            request = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return self._error_response(
                None, None, None, "ENVELOPE_INVALID", "帧不是合法 JSON")
        if not isinstance(request, dict):
            # 合法 JSON 但非对象（123/"abc"/[1,2]）不得杀死进程（评审 A1）
            return self._error_response(
                None, None, None, "ENVELOPE_INVALID", "信封必须是 JSON 对象")
        envelope = ProtocolEnvelope.from_dict(request, schema_path=self.schema_path)
        problems = envelope.validate()
        if problems:
            code = ("PROTOCOL_VERSION_MISMATCH"
                    if request.get("protocolVersion") != "2.0" else "ENVELOPE_INVALID")
            return self._error_response(
                request.get("requestId"), request.get("idempotencyKey"),
                request.get("sequence"), code, "; ".join(problems),
                request.get("messageType"))

        # 序号先于组/实例校验消费：被拒请求的序号也必须占位，否则其后所有
        # 合法请求命中 GAP，通道永久死锁（评审 A3）。缺口上报一次后自愈。
        accept_result = self._control_sequence.accept_inbound(int(request["sequence"]))
        if accept_result == "GAP":
            return self._error_response(
                request["requestId"], request.get("idempotencyKey"),
                request["sequence"], "SEQUENCE_GAP",
                "请求序号存在缺口，需先完成状态修复", request["messageType"])

        if request["exerciseGroupId"] != self.exercise_group_id:
            return self._error_response(
                request["requestId"], request.get("idempotencyKey"),
                request["sequence"], "EXERCISE_GROUP_MISMATCH",
                "请求训练组与当前 Adapter Runtime 不一致", request["messageType"])
        if request["engineInstanceId"] != self.engine_instance_id:
            return self._error_response(
                request["requestId"], request.get("idempotencyKey"),
                request["sequence"], "ENGINE_INSTANCE_MISMATCH",
                "请求引擎实例不是当前实例", request["messageType"])

        message_type = request["messageType"]
        handler = self.handlers.get(message_type)
        if handler is None:
            return self._error_response(
                request["requestId"], request.get("idempotencyKey"),
                request["sequence"], "MESSAGE_UNSUPPORTED",
                "没有已注册的处理器: %s" % message_type, message_type)

        key = request.get("idempotencyKey")
        cache_key = ("idempotency:" + key) if key else ("request:" + request["requestId"])
        checksum = self._cache.payload_checksum({
            "messageType": message_type,
            "exerciseGroupId": request["exerciseGroupId"],
            "engineInstanceId": request["engineInstanceId"],
            "payload": request.get("payload") or {},
        })
        if accept_result == "DUPLICATE" and not self._cache.contains(cache_key):
            return self._error_response(
                request["requestId"], key, request["sequence"],
                "SEQUENCE_DUPLICATE", "重复序号与已缓存请求不匹配", message_type)
        try:
            cached = self._cache.execute(
                cache_key, checksum, lambda: self._invoke_handler(handler, request.get("payload") or {}))
        except ProtocolError as failure:
            return self._error_response(
                request["requestId"], key, request["sequence"],
                failure.code, str(failure), message_type)
        except (KeyError, TypeError, ValueError) as failure:
            return self._error_response(
                request["requestId"], key, request["sequence"],
                "ENGINE_REJECTED", str(failure), message_type)
        except Exception as failure:  # engine boundary: one bad command must not kill the runtime
            return self._error_response(
                request["requestId"], key, request["sequence"],
                "ENGINE_ERROR", str(failure), message_type)
        body = dict(cached.result or {})
        body["replayed"] = bool(cached.replayed)
        body.setdefault("accepted", True)
        body.setdefault("code", "OK")
        body.setdefault("message", "accepted")
        body.setdefault("appliedEntityRevision", None)
        body.setdefault("resultChecksum", self._cache.payload_checksum(body))
        return self._response(request, body)

    def _response(self, request, payload):
        response_type = _ack_of(str(request["messageType"]))
        if request["messageType"] == "INSTRUCTION_APPLY" \
                and not bool(payload.get("accepted", True)):
            response_type = "INSTRUCTION_REJECTED"
        response = {
            "protocolVersion": "2.0",
            "messageKind": "RESPONSE",
            "messageType": response_type,
            "exerciseGroupId": request.get("exerciseGroupId"),
            "engineInstanceId": self.engine_instance_id,
            "requestId": "resp-" + uuid.uuid4().hex[:12],
            "correlationRequestId": request.get("requestId"),
            "idempotencyKey": request.get("idempotencyKey"),
            "sequence": self._control_sequence.next_outbound(),
            "systemTimeUtc": _utc_now(),
            "simulationTimeSeconds": self._simulation_time_supplier(),
            "payloadSchemaVersion": None,
            "payload": payload,
        }
        return _json_frame(response)

    @staticmethod
    def _invoke_handler(handler, payload):
        # Cache rejected commands too: repeating a rejected request must not execute it again.
        try:
            return handler(payload)
        except (KeyError, TypeError, ValueError) as failure:
            return {"accepted": False, "code": "ENGINE_REJECTED", "message": str(failure)}
        except Exception as failure:
            return {"accepted": False, "code": "ENGINE_ERROR", "message": str(failure)}

    def _error_response(self, request_id, idempotency_key, sequence, code, message,
                        request_message_type=None):
        payload = {"accepted": False, "code": code, "message": message,
                   "replayed": False, "appliedEntityRevision": None}
        payload["resultChecksum"] = self._cache.payload_checksum(payload)
        return _json_frame({
            "protocolVersion": "2.0",
            "messageKind": "RESPONSE",
            "messageType": ("INSTRUCTION_REJECTED" if request_message_type == "INSTRUCTION_APPLY"
                            else _ack_of(str(request_message_type)) if request_message_type else "HELLO_ACK"),
            "exerciseGroupId": self.exercise_group_id,
            "engineInstanceId": self.engine_instance_id,
            "requestId": "resp-" + uuid.uuid4().hex[:12],
            "correlationRequestId": request_id,
            "idempotencyKey": idempotency_key,
            "sequence": self._control_sequence.next_outbound(),
            "systemTimeUtc": _utc_now(),
            "simulationTimeSeconds": self._simulation_time_supplier(),
            "payloadSchemaVersion": None,
            "payload": payload,
        })

    def _publish_event(self, message_type, payload):
        with self._state_lock:
            socket = self._ensure_state_socket()
            event = {
                "protocolVersion": "2.0",
                "messageKind": "EVENT",
                "messageType": message_type,
                "exerciseGroupId": self.exercise_group_id,
                "engineInstanceId": self.engine_instance_id,
                "requestId": None,
                "correlationRequestId": None,
                "idempotencyKey": None,
                "sequence": self._state_sequence.next_outbound(),
                "systemTimeUtc": _utc_now(),
                "simulationTimeSeconds": self._simulation_time_supplier(),
                "payloadSchemaVersion": None,
                "payload": payload,
            }
            socket.send(_json_frame(event))

    def _ensure_state_socket(self):
        if self._state_socket is None:
            if self._context is None:
                self._context = zmq.Context()
            self._state_socket = self._context.socket(zmq.PUB)
            self._state_socket.setsockopt(zmq.LINGER, 0)
            self._state_socket.bind(self.state_endpoint)
        return self._state_socket

    @property
    def out_of_sync(self):
        return self._control_sequence.out_of_sync


def _ack_of(message_type):
    known = {
        "HELLO": "HELLO_ACK",
        "START": "STARTED",
        "PAUSE": "PAUSED",
        "RESUME": "RESUMED",
        "STOP": "STOPPED",
        "RESET_DEMO_ONLY": "RESET_DEMO_ONLY",
        "REFERENCE_SNAPSHOT_LOAD": "REFERENCE_SNAPSHOT_ACK",
        "SPECIAL_PROFILE_LOAD": "SPECIAL_PROFILE_ACK",
        "AIRCRAFT_APPLY": "AIRCRAFT_APPLIED",
        "AIRCRAFT_DELETE": "AIRCRAFT_DELETED",
        "AIRCRAFT_EXISTS_GET": "AIRCRAFT_EXISTS_RESULT",
        "INSTRUCTION_APPLY": "INSTRUCTION_APPLIED",
        "INSTRUCTION_CANCEL": "INSTRUCTION_CANCELLED",
        "INSTRUCTION_STATUS_GET": "INSTRUCTION_STATUS_RESULT",
        "RECOVERY_CHECKPOINT_LOAD": "RECOVERY_CHECKPOINT_ACK",
        "STATE_SNAPSHOT_GET": "STATE_SNAPSHOT_CHUNK",
    }
    return known.get(message_type, "HELLO_ACK")


def _utc_now():
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + ".000Z"


def _json_frame(value):
    frame = json.dumps(value, ensure_ascii=False).encode("utf-8")
    if len(frame) > MAX_FRAME_BYTES:
        raise ProtocolError("FRAME_TOO_LARGE", "帧超过 1 MiB 上限")
    return frame


def build_parser():
    parser = argparse.ArgumentParser(description="BlueSky Protocol 2.0 training adapter")
    parser.add_argument("--control-endpoint", required=True)
    parser.add_argument("--state-endpoint", required=True)
    parser.add_argument("--exercise-group-id", required=True)
    parser.add_argument("--engine-instance-id", required=True)
    parser.add_argument("--workdir", default=str(Path.cwd()))
    parser.add_argument("--schema-path", default=str(
        Path(__file__).resolve().parents[3] / "docs" / "contracts"
        / "adapter-protocol-v2.schema.json"))
    return parser


def main(argv=None):
    from .engine import BlueSkyEngine
    from .engine_v2 import EngineV2Handlers

    args = build_parser().parse_args(argv)
    import os
    runtime_dir = Path(args.workdir)
    runtime_dir.mkdir(parents=True, exist_ok=True)
    (runtime_dir / "process.json").write_text(json.dumps({"engineInstanceId": args.engine_instance_id, "pid": os.getpid()}), encoding="utf-8")
    engine = BlueSkyEngine(args.workdir)
    engine.initialize()
    bridge = EngineV2Handlers(engine)
    runtime = AdapterRuntime(
        bridge.handlers(), args.control_endpoint, args.state_endpoint,
        exercise_group_id=args.exercise_group_id,
        engine_instance_id=args.engine_instance_id,
        schema_path=args.schema_path,
        tick=engine.update,
        health_supplier=engine.health,
        simulation_time_supplier=engine.simulation_time,
        state_supplier=engine.snapshot)
    for name in ("SIGINT", "SIGTERM", "SIGBREAK"):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), lambda *_: runtime.shutdown())
    runtime.run_control_loop()
    # Written only after the control loop and sockets have stopped. Java may use
    # this receipt to reconcile a lost STOP acknowledgement after its own restart.
    (runtime_dir / "stopped.json").write_text(json.dumps({"engineInstanceId": args.engine_instance_id}), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
