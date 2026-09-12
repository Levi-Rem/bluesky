# -*- coding: utf-8 -*-
"""P04：Java—Python Adapter Protocol 2.0（详细设计 2.2 第 10 节）。

包含信封校验、双向序号与幂等结果缓存；消息类型枚举唯一来源是
docs/contracts/adapter-protocol-v2.schema.json，本模块不手抄消息清单。
"""
import hashlib
import json
import math
import os
import threading
import time
from datetime import datetime

# 模块位于 bluesky-master/bluesky/plugins/training_adapter/，仓库根需回溯三级
DEFAULT_SCHEMA_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "..", "docs", "contracts",
    "adapter-protocol-v2.schema.json")

REQUIRED_FIELDS = (
    "protocolVersion", "messageKind", "messageType", "exerciseGroupId",
    "engineInstanceId", "requestId", "correlationRequestId", "idempotencyKey",
    "sequence", "systemTimeUtc", "simulationTimeSeconds",
    "payloadSchemaVersion", "payload",
)
NON_NULL_FIELDS = (
    "protocolVersion", "messageKind", "messageType", "exerciseGroupId",
    "engineInstanceId", "sequence", "systemTimeUtc", "simulationTimeSeconds",
    "payload",
)
MESSAGE_KINDS = ("REQUEST", "RESPONSE", "EVENT")
MAX_FRAME_BYTES = 1024 * 1024


def _load_message_types(schema_path):
    with open(schema_path, "r", encoding="utf-8") as handle:
        schema = json.load(handle)
    return tuple(schema["properties"]["messageType"]["enum"])


class ProtocolError(Exception):
    """协议校验失败；code 可映射为引擎告警或协议拒绝。"""

    def __init__(self, code, message, problems=None):
        super(ProtocolError, self).__init__(message)
        self.code = code
        self.problems = problems or []


class ProtocolEnvelope(object):
    """2.0 信封：from_dict/to_dict/validate；也可作为无状态校验器使用。"""

    def __init__(self, values=None, schema_path=None, message_types=None):
        self.values = dict(values) if values is not None else {}
        self._message_types = message_types
        self._schema_path = schema_path

    @classmethod
    def from_dict(cls, values, schema_path=None):
        return cls(values, schema_path=schema_path)

    def _message_type_set(self):
        if self._message_types is None:
            path = self._schema_path or DEFAULT_SCHEMA_PATH
            self._message_types = _load_message_types(os.path.normpath(path))
        return self._message_types

    def to_dict(self):
        return dict(self.values)

    def validate(self, values=None, correlates_to_request_id=None):
        problems = []
        values = self.values if values is None else values
        for field in REQUIRED_FIELDS:
            if field not in values:
                problems.append("信封缺少字段: %s" % field)
        for field in NON_NULL_FIELDS:
            if field in values and values.get(field) is None:
                problems.append("信封字段不能为空: %s" % field)
        if problems:
            return problems

        if values.get("protocolVersion") != "2.0":
            problems.append("protocolVersion 必须为 2.0")
        kind = values.get("messageKind")
        if kind not in MESSAGE_KINDS:
            problems.append("messageKind 非法: %s" % kind)
        if values.get("messageType") not in self._message_type_set():
            problems.append("messageType 不在枚举内: %s" % values.get("messageType"))
        if not values.get("exerciseGroupId"):
            problems.append("exerciseGroupId 不能为空")
        if not values.get("engineInstanceId"):
            problems.append("engineInstanceId 不能为空")
        sequence = values.get("sequence")
        if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 1:
            problems.append("sequence 必须为不小于 1 的整数")
        system_time = values.get("systemTimeUtc")
        if not system_time:
            problems.append("systemTimeUtc 不能为空")
        else:
            try:
                if not str(system_time).endswith("Z"):
                    raise ValueError("not UTC")
                datetime.fromisoformat(str(system_time)[:-1] + "+00:00")
            except (TypeError, ValueError):
                problems.append("systemTimeUtc 必须是 ISO-8601 UTC 时间")
        simulation_time = values.get("simulationTimeSeconds")
        if (not isinstance(simulation_time, (int, float))
                or isinstance(simulation_time, bool)
                or not math.isfinite(simulation_time) or simulation_time < 0):
            problems.append("simulationTimeSeconds 必须为非负有限数值")
        if not isinstance(values.get("payload"), dict):
            problems.append("payload 必须是对象")

        if kind == "REQUEST":
            if values.get("correlationRequestId") is not None:
                problems.append("REQUEST 的 correlationRequestId 必须为空")
            if not values.get("requestId"):
                problems.append("REQUEST 必须携带 requestId")
        elif kind == "RESPONSE":
            if not values.get("correlationRequestId"):
                problems.append("RESPONSE 必须携带 correlationRequestId")
            if not values.get("requestId"):
                problems.append("RESPONSE 必须携带 requestId")
            if correlates_to_request_id is not None \
                    and values.get("correlationRequestId") != correlates_to_request_id:
                problems.append(
                    "RESPONSE 的 correlationRequestId 必须等于对应 REQUEST 的 requestId")
        return problems


class SequenceTracker(object):
    """双向序号：重复忽略、缺口 OUT_OF_SYNC、实例切换重置（详细设计 10.1.3）。"""

    def __init__(self):
        self._lock = threading.RLock()
        self.reset_for_instance(None)

    def reset_for_instance(self, engine_instance_id):
        with self._lock:
            self.engine_instance_id = engine_instance_id
            self._last_outbound = 0
            self._last_inbound = 0
            self.out_of_sync = False

    def next_outbound(self):
        with self._lock:
            self._last_outbound += 1
            return self._last_outbound

    def accept_inbound(self, sequence):
        with self._lock:
            if sequence <= self._last_inbound:
                return "DUPLICATE"
            if self.out_of_sync:
                # 恢复路径（评审 A3）：缺口已上报后，以最新到达序号为新基准
                # 继续递增，清除 OUT_OF_SYNC——通道不得因一次缺口永久死锁
                self._last_inbound = sequence
                self.out_of_sync = False
                return "IN_ORDER"
            if sequence > self._last_inbound + 1:
                self.out_of_sync = True
                return "GAP"
            self._last_inbound = sequence
            return "IN_ORDER"


class IdempotencyPayloadMismatch(ProtocolError):
    """同键异载荷：无副作用拒绝（详细设计 10.1.6）。"""

    def __init__(self, key):
        super(IdempotencyPayloadMismatch, self).__init__(
            "IDEMPOTENCY_PAYLOAD_MISMATCH",
            "幂等键 %s 已用于不同 payload checksum" % key)


class CachedResult(object):
    def __init__(self, replayed, result):
        self.replayed = replayed
        self.result = result


class IdempotencyResultCache(object):
    """同键同结果、异载荷拒绝；缓存保留训练组生命周期 + 24 小时。"""

    RETENTION_MILLIS = 24 * 3600 * 1000

    def __init__(self, now_ms=None):
        self._now_ms = now_ms or (lambda: int(time.time() * 1000))
        self._results = {}

    @staticmethod
    def payload_checksum(payload):
        canonical = json.dumps(payload, sort_keys=True, ensure_ascii=False,
                               separators=(",", ":"))
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def execute(self, key, payload_checksum, action):
        cached = self._results.get(key)
        if cached is not None:
            checksum, result, _ = cached
            if checksum != payload_checksum:
                raise IdempotencyPayloadMismatch(key)
            self._results[key] = (checksum, result, self._now_ms())
            return CachedResult(True, result)
        result = action()
        self._results[key] = (payload_checksum, result, self._now_ms())
        return CachedResult(False, result)

    def purge_expired(self, now_ms=None):
        reference = self._now_ms() if now_ms is None else now_ms
        deadline = reference - self.RETENTION_MILLIS
        expired = [key for key, (_, _, created) in self._results.items()
                   if created <= deadline]
        for key in expired:
            del self._results[key]
        return expired

    def size(self):
        return len(self._results)

    def contains(self, key):
        return key in self._results
