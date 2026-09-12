#!/usr/bin/env python3
"""模拟飞行员工作台第二版 AT-01～AT-12 业务验收入口。

本脚本只使用 Python 标准库，面向已启动的真实 `/api/v2` 服务。它不会把
HTTP 404、环境缺项或未执行的故障注入算作通过，并为每次 HTTP 调用保留
脱敏后的 requestId、operationId、状态码与响应摘要。
"""

from __future__ import annotations

import argparse
import concurrent.futures
import copy
import datetime as dt
import json
import os
import random
import re
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple


PASS = "PASS"
FAIL = "FAIL"
BLOCKED = "BLOCKED"
TERMINAL_INSTRUCTION_STATES = {"COMPLETED", "FAILED", "CANCELLED"}


class AcceptanceFailure(AssertionError):
    pass


class AcceptanceBlocked(RuntimeError):
    pass


@dataclass
class HttpResult:
    method: str
    path: str
    status: int
    headers: Dict[str, str]
    body: Any
    elapsed_ms: float


@dataclass
class StepResult:
    name: str
    status: str
    started_at: str
    elapsed_ms: float
    message: str = ""


@dataclass
class ScenarioResult:
    scenario_id: str
    name: str
    status: str = PASS
    steps: List[StepResult] = field(default_factory=list)
    error: str = ""


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def deep_get(value: Any, *names: str, default: Any = None) -> Any:
    current = value
    for name in names:
        if not isinstance(current, Mapping) or name not in current:
            return default
        current = current[name]
    return current


def first_present(value: Mapping[str, Any], *names: str, default: Any = None) -> Any:
    for name in names:
        if name in value and value[name] is not None:
            return value[name]
    return default


def collect_named_values(value: Any, keys: Iterable[str]) -> List[str]:
    wanted = set(keys)
    found: List[str] = []
    if isinstance(value, Mapping):
        for key, item in value.items():
            if key in wanted and item is not None:
                found.append(str(item))
            found.extend(collect_named_values(item, wanted))
    elif isinstance(value, list):
        for item in value:
            found.extend(collect_named_values(item, wanted))
    return found


def redact(value: Any) -> Any:
    if isinstance(value, Mapping):
        result: Dict[str, Any] = {}
        for key, item in value.items():
            lowered = key.lower()
            if any(token in lowered for token in ("secret", "password", "privatekey")):
                result[key] = "<redacted>"
            elif "fingerprint" in lowered:
                text = str(item)
                result[key] = text[:8] + "..." if len(text) > 8 else "<redacted>"
            else:
                result[key] = redact(item)
        return result
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


class EvidenceRecorder:
    def __init__(self, run_id: str, seed: int) -> None:
        self.run_id = run_id
        self.seed = seed
        self.started_at = utc_now()
        self.http_calls: List[Dict[str, Any]] = []
        self.request_ids: List[str] = []
        self.operation_ids: List[str] = []

    def record_http(self, result: HttpResult, request_id: str, request_body: Any) -> None:
        response_request_ids = collect_named_values(result.body, ("requestId", "request_id"))
        header_request_id = result.headers.get("x-request-id")
        ids = [request_id, header_request_id, *response_request_ids]
        for value in ids:
            if value and value not in self.request_ids:
                self.request_ids.append(value)
        for operation_id in collect_named_values(
                result.body, ("operationId", "operation_id")):
            if operation_id not in self.operation_ids:
                self.operation_ids.append(operation_id)
        self.http_calls.append({
            "at": utc_now(),
            "method": result.method,
            "path": result.path,
            "status": result.status,
            "elapsedMs": round(result.elapsed_ms, 3),
            "requestId": header_request_id or request_id,
            "requestBody": redact(request_body),
            "responseBody": redact(result.body),
        })


class Identity:
    def __init__(self, caller_type: str, caller_id: str,
                 terminal_id: Optional[str] = None,
                 fingerprint_digest: Optional[str] = None) -> None:
        self.caller_type = caller_type
        self.caller_id = caller_id
        self.terminal_id = terminal_id
        self.fingerprint_digest = fingerprint_digest

    @classmethod
    def from_config(cls, data: Mapping[str, Any], default_type: str) -> "Identity":
        caller_id = str(data.get("callerId") or data.get("terminalId") or "").strip()
        if not caller_id:
            raise AcceptanceBlocked(f"{default_type} 身份缺少 callerId/terminalId")
        return cls(str(data.get("callerType") or default_type), caller_id,
                   data.get("terminalId"), data.get("fingerprintDigest"))

    def headers(self, gateway_secret: str) -> Dict[str, str]:
        if not gateway_secret:
            raise AcceptanceBlocked("业务 HTTP 场景缺少网关密钥环境变量")
        headers = {
            "X-Gateway-Secret": gateway_secret,
            "X-Trusted-Caller-Type": self.caller_type,
            "X-Trusted-Caller-Id": self.caller_id,
        }
        if self.terminal_id:
            headers["X-Trusted-Terminal-Id"] = str(self.terminal_id)
        if self.fingerprint_digest:
            headers["X-Trusted-Fingerprint-Digest"] = str(self.fingerprint_digest)
        return headers


class ApiClient:
    def __init__(self, base_url: str, gateway_secret: str,
                 recorder: EvidenceRecorder, timeout_seconds: float = 10.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.gateway_secret = gateway_secret
        self.recorder = recorder
        self.timeout_seconds = timeout_seconds

    def request(self, method: str, path: str, identity: Identity,
                body: Any = None, expected: Sequence[int] = (200,),
                headers: Optional[Mapping[str, str]] = None,
                timeout_seconds: Optional[float] = None) -> HttpResult:
        request_id = f"bat-{self.recorder.run_id}-{len(self.recorder.http_calls) + 1:05d}"
        all_headers = identity.headers(self.gateway_secret)
        all_headers["Accept"] = "application/json"
        all_headers["X-Request-Id"] = request_id
        if headers:
            all_headers.update({str(k): str(v) for k, v in headers.items()})
        encoded = None
        if body is not None:
            encoded = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            all_headers["Content-Type"] = "application/json; charset=utf-8"
        started = time.perf_counter()
        status = 0
        response_headers: Dict[str, str] = {}
        response_body: Any = None
        try:
            req = urllib.request.Request(
                self.base_url + path, data=encoded, headers=all_headers, method=method)
            with urllib.request.urlopen(
                    req, timeout=timeout_seconds or self.timeout_seconds) as response:
                status = response.status
                response_headers = {key.lower(): value for key, value in response.headers.items()}
                raw = response.read()
        except urllib.error.HTTPError as error:
            status = error.code
            response_headers = {key.lower(): value for key, value in error.headers.items()}
            raw = error.read()
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise AcceptanceBlocked(f"无法访问 {method} {path}: {error}") from error
        elapsed_ms = (time.perf_counter() - started) * 1000
        if raw:
            text = raw.decode("utf-8", errors="replace")
            try:
                response_body = json.loads(text)
            except json.JSONDecodeError:
                response_body = text[:4000]
        result = HttpResult(method, path, status, response_headers, response_body, elapsed_ms)
        self.recorder.record_http(result, request_id, body)
        if status not in expected:
            code = deep_get(response_body, "code", default="")
            suffix = f" ({code})" if code else ""
            raise AcceptanceFailure(
                f"{method} {path} 期望 {list(expected)}，实际 {status}{suffix}: "
                f"{str(response_body)[:500]}")
        return result


class AcceptanceContext:
    def __init__(self, config: Mapping[str, Any], recorder: EvidenceRecorder) -> None:
        self.config = dict(config)
        secret_env = str(config.get("gatewaySecretEnv") or "BS_ACCEPTANCE_GATEWAY_SECRET")
        gateway_secret = os.environ.get(secret_env, "")
        self.gateway_secret_env = secret_env
        self.recorder = recorder
        self.random = random.Random(int(config.get("seed", 20260819)))
        # 幂等键必须轮内可重试、轮间不撞库：固定 seed 的确定性键会在复跑时
        # 命中平台持久化幂等缓存（同键不同体 → 409），故混入轮次令牌
        self.run_token = f"{int(time.time() * 1000):x}-{os.getpid():x}"
        self.client = ApiClient(str(config.get("baseUrl") or "http://127.0.0.1:8080"),
                                gateway_secret, recorder,
                                float(config.get("httpTimeoutSeconds", 10)))

    def require(self, key: str) -> Any:
        value = self.config.get(key)
        if value is None or value == "" or value == {} or value == []:
            raise AcceptanceBlocked(f"配置缺少 {key}")
        return value

    def identity(self, key: str, default_type: str) -> Identity:
        value = self.require(key)
        if not isinstance(value, Mapping):
            raise AcceptanceBlocked(f"配置 {key} 必须是对象")
        return Identity.from_config(value, default_type)

    def terminal(self, key: str = "sourceTerminal") -> Identity:
        return self.identity(key, "TERMINAL")

    def operations(self) -> Identity:
        return self.identity("operations", "OPERATIONS")

    def orchestrator(self) -> Identity:
        return self.identity("orchestrator", "EXERCISE_ORCHESTRATOR")

    def key(self, prefix: str) -> str:
        return f"{prefix}-{self.run_token}-{self.random.getrandbits(64):016x}"


class Scenario:
    scenario_id = "AT-00"
    name = "未命名"

    def __init__(self, context: AcceptanceContext) -> None:
        self.ctx = context
        self.result = ScenarioResult(self.scenario_id, self.name)

    def step(self, name: str, action: Callable[[], Any]) -> Any:
        started_at = utc_now()
        started = time.perf_counter()
        try:
            value = action()
            self.result.steps.append(StepResult(
                name, PASS, started_at, (time.perf_counter() - started) * 1000))
            return value
        except AcceptanceBlocked as error:
            self.result.steps.append(StepResult(
                name, BLOCKED, started_at, (time.perf_counter() - started) * 1000, str(error)))
            raise
        except Exception as error:
            self.result.steps.append(StepResult(
                name, FAIL, started_at, (time.perf_counter() - started) * 1000, str(error)))
            raise

    def execute(self) -> ScenarioResult:
        try:
            self.run()
        except AcceptanceBlocked as error:
            self.result.status = BLOCKED
            self.result.error = str(error)
        except Exception as error:
            self.result.status = FAIL
            self.result.error = str(error)
        return self.result

    def run(self) -> None:
        raise NotImplementedError

    def group_id(self) -> str:
        return str(self.ctx.require("groupId"))

    def aircraft_id(self) -> str:
        return str(self.ctx.require("aircraftId"))

    def aircraft(self, identity: Optional[Identity] = None) -> Mapping[str, Any]:
        result = self.ctx.client.request(
            "GET", f"/api/v2/aircraft/{self.aircraft_id()}",
            identity or self.ctx.terminal(), expected=(200,))
        if not isinstance(result.body, Mapping):
            raise AcceptanceFailure("航空器响应不是 JSON 对象")
        return result.body

    def aircraft_revision(self, identity: Optional[Identity] = None) -> int:
        aircraft = self.aircraft(identity)
        revision = first_present(aircraft, "revision", default=self.ctx.config.get("aircraftRevision"))
        if revision is None:
            raise AcceptanceBlocked("航空器响应和配置都没有 aircraftRevision")
        return int(revision)

    def submit_text(self, text: str, identity: Optional[Identity] = None,
                    expected: Sequence[int] = (202,), scheduling: str = "REPLACE") -> HttpResult:
        terminal = identity or self.ctx.terminal()
        return self.ctx.client.request(
            "POST", f"/api/v2/aircraft/{self.aircraft_id()}/instructions", terminal,
            {"aircraftRevision": self.aircraft_revision(terminal),
             "scheduling": scheduling, "text": text},
            expected=expected, headers={"Idempotency-Key": self.ctx.key("ins")})

    def await_instruction(self, instruction: Mapping[str, Any],
                          accepted_states: Optional[Iterable[str]] = None) -> Mapping[str, Any]:
        instruction_id = first_present(instruction, "id", "instructionId", "instruction_id")
        if not instruction_id:
            raise AcceptanceFailure("受理响应缺少 instructionId/id")
        accepted = set(accepted_states or TERMINAL_INSTRUCTION_STATES)
        timeout = float(self.ctx.config.get("instructionTimeoutSeconds", 30))
        deadline = time.monotonic() + timeout
        last: Mapping[str, Any] = instruction
        while time.monotonic() < deadline:
            response = self.ctx.client.request(
                "GET", f"/api/v2/instructions/{instruction_id}", self.ctx.terminal(),
                expected=(200,))
            if isinstance(response.body, Mapping):
                last = response.body
                status = str(first_present(last, "status", default=""))
                if status in accepted:
                    return last
                if status in TERMINAL_INSTRUCTION_STATES:
                    raise AcceptanceFailure(
                        f"指令 {instruction_id} 到达非预期终态 {status}，期望 {sorted(accepted)}")
            time.sleep(float(self.ctx.config.get("pollIntervalSeconds", 0.5)))
        raise AcceptanceFailure(
            f"指令 {instruction_id} 在 {timeout}s 内未到期望终态，最后状态="
            f"{first_present(last, 'status', default='UNKNOWN')}")

    def command_sequence(self, config_key: str, required_types: Iterable[str]) -> None:
        commands = self.ctx.require(config_key)
        if not isinstance(commands, list):
            raise AcceptanceBlocked(f"{config_key} 必须是命令数组")
        configured_types = {str(item.get("type", "")).upper()
                            for item in commands if isinstance(item, Mapping)}
        missing = set(required_types) - configured_types
        if missing:
            raise AcceptanceBlocked(f"{config_key} 缺少设计必验命令: {sorted(missing)}")
        for command in commands:
            if not isinstance(command, Mapping) or not command.get("text"):
                raise AcceptanceBlocked(f"{config_key} 命令必须含 type/text")
            response = self.submit_text(str(command["text"]))
            if not isinstance(response.body, Mapping):
                raise AcceptanceFailure(f"命令 {command['text']} 响应不是对象")
            expected_final = command.get("expectedFinalStatuses", ["COMPLETED"])
            self.await_instruction(response.body, expected_final)


class CompleteFlightAcceptanceTest(Scenario):
    scenario_id = "AT-01"
    name = "完整飞行"

    def runCompleteFlight(self) -> None:
        self.step("航空器处于活动状态", self._assert_active)
        self.step("执行完整飞行命令链", lambda: self.command_sequence(
            "at01Commands", {"TAKEOFF", "SIDSTAR", "HDG", "ALT", "SPD", "DCT",
                             "RTE", "HOLD", "ILS"}))
        self.step("命令与飞行报告完整", self._assert_reports)

    def run(self) -> None:
        self.runCompleteFlight()

    def _assert_active(self) -> None:
        lifecycle = str(first_present(self.aircraft(), "lifecycle", default=""))
        if lifecycle != "ACTIVE":
            raise AcceptanceBlocked(f"AT-01 要求 ACTIVE 航空器，当前 {lifecycle or 'UNKNOWN'}")

    def _assert_reports(self) -> None:
        command_response = self.ctx.client.request(
            "GET", f"/api/v2/exercise-groups/{self.group_id()}"
                   "/reports?reportKind=COMMAND&pageSize=200",
            self.ctx.terminal(), expected=(200,))
        expected_events = set(self.ctx.config.get(
            "at01ExpectedFlightEvents", ["TAKEOFF", "LANDED"]))
        # LANDED 等飞行事件在指令收敛之后才发生（ILS 截获即收敛、落地在其后）：
        # 断言前对期望事件做有界等待，避免与物理过程赛跑
        timeout = float(self.ctx.config.get("instructionTimeoutSeconds", 30))
        deadline = time.monotonic() + timeout
        flight_items = []
        actual_events = set()
        while True:
            flight_response = self.ctx.client.request(
                "GET", f"/api/v2/exercise-groups/{self.group_id()}"
                       "/reports?reportKind=FLIGHT&pageSize=200",
                self.ctx.terminal(), expected=(200,))
            flight_items = deep_get(flight_response.body, "items", default=[])
            actual_events = set(collect_named_values(
                flight_items, ("eventType", "event_type")))
            if expected_events <= actual_events or time.monotonic() >= deadline:
                break
            time.sleep(float(self.ctx.config.get("pollIntervalSeconds", 0.5)))
        command_items = deep_get(command_response.body, "items", default=[])
        expected_command_types = {str(item["type"]).upper()
                                  for item in self.ctx.require("at01Commands")}
        actual_command_types = set(collect_named_values(
            command_items, ("instructionType", "instruction_type", "commandType")))
        missing_commands = expected_command_types - actual_command_types
        if missing_commands:
            raise AcceptanceFailure(f"命令报告缺少类型: {sorted(missing_commands)}")
        missing_events = expected_events - actual_events
        if missing_events:
            raise AcceptanceFailure(f"飞行报告缺少事件: {sorted(missing_events)}")


class MissedApproachAcceptanceTest(Scenario):
    scenario_id = "AT-02"
    name = "复飞"

    def runMissedApproach(self) -> None:
        self.step("执行 ILS 与 MISSED", lambda: self.command_sequence(
            "at02Commands", {"ILS", "MISSED"}))
        self.step("存在复飞终态记录", self._assert_missed_record)

    def run(self) -> None:
        self.runMissedApproach()

    def _assert_missed_record(self) -> None:
        result = self.ctx.client.request(
            "GET", f"/api/v2/aircraft/{self.aircraft_id()}/instructions",
            self.ctx.terminal(), expected=(200,))
        items = deep_get(result.body, "items", default=[])
        missed = [item for item in items if str(first_present(item, "type", "instruction_type",
                                                               default="")) == "MISSED"]
        if not missed:
            raise AcceptanceFailure("指令历史中没有 MISSED")
        ils = [item for item in items if str(first_present(item, "type", "instruction_type",
                                                            default="")) == "ILS"]
        if not ils:
            raise AcceptanceFailure("指令历史中没有 ILS")
        active = {"RECEIVED", "VALIDATED", "BLOCKED", "DISPATCHING", "EXECUTING"}
        if str(first_present(ils[0], "status", default="")) in active:
            raise AcceptanceFailure("MISSED 后 ILS 仍处于活动状态")
        report = self.ctx.client.request(
            "GET", f"/api/v2/exercise-groups/{self.group_id()}"
                   "/reports?reportKind=FLIGHT&eventType=MISSED_APPROACH&pageSize=20",
            self.ctx.terminal(), expected=(200,))
        if not deep_get(report.body, "items", default=[]):
            raise AcceptanceFailure("没有 MISSED_APPROACH 飞行报告")


class MultiTerminalHandoverAcceptanceTest(Scenario):
    scenario_id = "AT-03"
    name = "多席位移交"

    def runConcurrentHandover(self) -> None:
        source = self.ctx.terminal("sourceTerminal")
        target = self.ctx.terminal("targetTerminal")
        target_frequency = float(self.ctx.require("targetFrequencyMhz"))
        revision = self.aircraft_revision(source)
        handover = self.step("源席按频率直接移交", lambda: self.ctx.client.request(
            "POST", f"/api/v2/aircraft/{self.aircraft_id()}/handover", source,
            {"aircraftRevision": revision, "targetFrequencyMhz": target_frequency},
            expected=(200,), headers={"Idempotency-Key": self.ctx.key("handover")}))
        if str(deep_get(handover.body, "targetTerminalId", default="")) != target.terminal_id:
            raise AcceptanceFailure("移交响应的目标终端不匹配")
        self.step("源席立即只读", lambda: self.submit_text("IDENT", source, expected=(403,)))
        self.step("目标席立即可写", lambda: self._submit_target_ident(target))
        self.step("移交不取消原活动指令且目标席可取消既有队列",
                  lambda: self._assert_queue_ownership(target))
        self.step("伪造终端身份被拒绝", self._assert_forged_identity_rejected)
        self.step("并发二次移交只允许一个提交成功", lambda: self._race_handover(target))

    def run(self) -> None:
        self.runConcurrentHandover()

    def _submit_target_ident(self, target: Identity) -> None:
        result = self.submit_text("IDENT", target)
        if str(deep_get(result.body, "status", default="")) != "COMPLETED":
            raise AcceptanceFailure("IDENT 业务字段指令应同步完成")

    def _assert_forged_identity_rejected(self) -> None:
        source_data = copy.deepcopy(self.ctx.require("sourceTerminal"))
        source_data["terminalId"] = "FORGED-TERMINAL"
        forged = Identity.from_config(source_data, "TERMINAL")
        self.ctx.client.request(
            "GET", f"/api/v2/workstations/FORGED-TERMINAL/bootstrap", forged,
            expected=(403,))

    def _assert_queue_ownership(self, target: Identity) -> None:
        active_id = str(self.ctx.require("handoverActiveInstructionId"))
        queued_id = str(self.ctx.require("handoverCancelableInstructionId"))
        active = self.ctx.client.request(
            "GET", f"/api/v2/instructions/{active_id}", target, expected=(200,))
        if str(deep_get(active.body, "status", default="")) == "CANCELLED":
            raise AcceptanceFailure("移交错误取消了原活动指令")
        queued = self.ctx.client.request(
            "GET", f"/api/v2/instructions/{queued_id}", target, expected=(200,))
        self.ctx.client.request(
            "POST", f"/api/v2/instructions/{queued_id}/actions/cancel", target,
            {"instructionRevision": first_present(queued.body, "revision", default=1)},
            expected=(200,), headers={"Idempotency-Key": self.ctx.key("cancel-after-handover")})

    def _race_handover(self, current: Identity) -> None:
        race_targets = self.ctx.require("handoverRaceTargets")
        if not isinstance(race_targets, list) or len(race_targets) != 2:
            raise AcceptanceBlocked("handoverRaceTargets 必须配置两个不同目标频率")
        revision = self.aircraft_revision(current)

        def submit(frequency: Any) -> int:
            result = self.ctx.client.request(
                "POST", f"/api/v2/aircraft/{self.aircraft_id()}/handover", current,
                {"aircraftRevision": revision, "targetFrequencyMhz": float(frequency)},
                expected=(200, 403, 409),
                headers={"Idempotency-Key": self.ctx.key("handover-race")})
            return result.status

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(submit, race_targets))
        if statuses.count(200) != 1 or not all(status in (200, 403, 409) for status in statuses):
            raise AcceptanceFailure(f"并发移交结果应恰好一个成功，实际 {statuses}")


class RouteConstraintAcceptanceTest(Scenario):
    scenario_id = "AT-04"
    name = "航路与约束"

    def runAtomicRouteScenario(self) -> None:
        self.step("执行航路与约束命令", lambda: self.command_sequence(
            "at04Commands", {"RTE", "RESUME", "OFFSET", "VOR", "P_LEVEL", "P_TIME"}))
        before = self.step("保存错误输入前航路", self.aircraft)
        invalid = str(self.ctx.config.get("at04InvalidRouteCommand") or "RTE UNKNOWN_POINT")
        self.step("错误航路被明确拒绝", lambda: self.submit_text(invalid, expected=(400, 404, 422)))
        after = self.step("读取错误输入后航路", self.aircraft)
        before_plans = first_present(before, "flightPlans", "flight_plans", default=[])
        after_plans = first_present(after, "flightPlans", "flight_plans", default=[])
        if before_plans != after_plans:
            raise AcceptanceFailure("错误航路改变了飞行计划，原子回滚失败")

    def run(self) -> None:
        self.runAtomicRouteScenario()


class HoldingOrbitAcceptanceTest(Scenario):
    scenario_id = "AT-05"
    name = "等待与盘旋"

    def runHoldAndOrbit(self) -> None:
        self.step("执行 HOLD/ORBIT/退出命令", lambda: self.command_sequence(
            "at05Commands", {"HOLD", "ORBIT"}))
        self.step("暂停时新盘旋指令进入阻塞态", self._assert_pause_freeze)

    def run(self) -> None:
        self.runHoldAndOrbit()

    def _group_revision(self) -> int:
        return int(self._group()["revision"])

    def _group(self) -> Mapping[str, Any]:
        response = self.ctx.client.request("GET", "/api/v2/exercise-groups",
                                           self.ctx.operations(), expected=(200,))
        for group in deep_get(response.body, "items", default=[]):
            if str(group.get("id")) == self.group_id():
                return group
        raise AcceptanceFailure("训练组列表中找不到目标组")

    def _wait_group_state(self, expected: str) -> None:
        timeout = float(self.ctx.config.get("groupTransitionTimeoutSeconds", 15))
        deadline = time.monotonic() + timeout
        last = "UNKNOWN"
        while time.monotonic() < deadline:
            last = str(self._group().get("state"))
            if last == expected:
                return
            if last in ("RECOVERY_FAILED", "ENDED"):
                break
            time.sleep(0.5)
        raise AcceptanceFailure(f"训练组未在 {timeout}s 内进入 {expected}，最后状态 {last}")

    def _assert_pause_freeze(self) -> None:
        revision = self._group_revision()
        self.ctx.client.request(
            "POST", f"/api/v2/exercise-groups/{self.group_id()}/actions/pause",
            self.ctx.terminal(), {"groupRevision": revision}, expected=(202,),
            headers={"Idempotency-Key": self.ctx.key("pause")})
        self._wait_group_state("PAUSED")
        text = str(self.ctx.config.get("at05PauseProbeCommand") or "ORBIT R")
        result = self.submit_text(text)
        if str(deep_get(result.body, "status", default="")) != "BLOCKED":
            raise AcceptanceFailure("暂停期间指令未进入 BLOCKED")
        revision = self._group_revision()
        self.ctx.client.request(
            "POST", f"/api/v2/exercise-groups/{self.group_id()}/actions/resume",
            self.ctx.terminal(), {"groupRevision": revision}, expected=(202,),
            headers={"Idempotency-Key": self.ctx.key("resume")})
        self._wait_group_state("RUNNING")


class TransponderAcceptanceTest(Scenario):
    scenario_id = "AT-06"
    name = "Squawk 与 SSR"

    def runTransponderMatrix(self) -> None:
        self.step("前导零 Squawk 被保留", lambda: self._completed("SQK 0042"))
        self.step("读取时仍保留四位前导零", self._assert_squawk_preserved)
        self.step("非法八进制被拒绝", lambda: self.submit_text("SQK 8888", expected=(400,)))
        self.step("0000 被明确拒绝", lambda: self.submit_text("SQK 0000", expected=(400,)))
        self.step("SSR 模式切换", lambda: self._completed("SSRMODE A"))
        self.step("IDENT 与 ID 不混用", self._ident_not_id)
        self.step("IDENT 按时自动清除", self._assert_ident_auto_clear)
        self.step("NML 恢复计划值", lambda: self._completed("NML"))
        self.step("重启后应答机状态恢复", self._assert_restart_evidence)

    def run(self) -> None:
        self.runTransponderMatrix()

    def _completed(self, text: str) -> Mapping[str, Any]:
        result = self.submit_text(text)
        status = str(deep_get(result.body, "status", default=""))
        if status != "COMPLETED":
            raise AcceptanceFailure(f"{text} 应同步 COMPLETED，实际 {status}")
        return result.body

    def _ident_not_id(self) -> None:
        result = self._completed("IDENT")
        instruction_type = first_present(result, "instruction_type", "type", default="")
        if str(instruction_type) != "IDENT":
            raise AcceptanceFailure(f"IDENT 被错误解析为 {instruction_type}")

    def _assert_squawk_preserved(self) -> None:
        aircraft = self.aircraft()
        squawk = first_present(aircraft, "squawkCode", "squawk_code", "transponderCode")
        if str(squawk) != "0042":
            raise AcceptanceFailure(f"Squawk 前导零未保留，实际 {squawk!r}")

    def _assert_ident_auto_clear(self) -> None:
        timeout = float(self.ctx.config.get("identAutoClearTimeoutSeconds", 35))
        deadline = time.monotonic() + timeout
        observed_active = False
        while time.monotonic() < deadline:
            aircraft = self.aircraft()
            active = first_present(aircraft, "identActive", "ident_active")
            if active is True or str(active).lower() in ("true", "1"):
                observed_active = True
            if observed_active and (active is False or str(active).lower() in ("false", "0")):
                return
            time.sleep(0.5)
        raise AcceptanceFailure("未观察到 IDENT 激活后自动清除")

    def _assert_restart_evidence(self) -> None:
        evidence = self.ctx.config.get("at06RestartEvidence")
        if not isinstance(evidence, Mapping):
            raise AcceptanceBlocked("缺少 at06RestartEvidence，未验证重启持久化")
        if not evidence.get("stateRestored") or not evidence.get("requestIds"):
            raise AcceptanceFailure("应答机重启恢复证据不完整")


class FakeTargetAcceptanceTest(Scenario):
    scenario_id = "AT-07"
    name = "假目标"

    def runBothTargetKinds(self) -> None:
        payloads = self.ctx.require("at07Targets")
        if not isinstance(payloads, list) or {item.get("targetKind") for item in payloads} != {
                "RADAR_SYNTHETIC", "SIMULATED_AIRCRAFT"}:
            raise AcceptanceBlocked("at07Targets 必须各含一种假目标 payload")
        for payload in payloads:
            target = self.step(f"创建 {payload['targetKind']}", lambda p=payload:
                               self.ctx.client.request(
                                   "POST", f"/api/v2/exercise-groups/{self.group_id()}/fake-targets",
                                   self.ctx.terminal(), p, expected=(201, 202),
                                   headers={"Idempotency-Key": self.ctx.key("fake")}))
            target_id = first_present(target.body, "id", "targetId")
            if not target_id:
                raise AcceptanceFailure("假目标创建响应缺少 id")
            self.step("查询假目标", lambda tid=target_id: self.ctx.client.request(
                "GET", f"/api/v2/fake-targets/{tid}", self.ctx.terminal(), expected=(200,)))
            self.step("停止假目标", lambda tid=target_id: self.ctx.client.request(
                "POST", f"/api/v2/fake-targets/{tid}/actions/stop", self.ctx.terminal(),
                {"targetRevision": 1}, expected=(200, 202),
                headers={"Idempotency-Key": self.ctx.key("fake-stop")}))
            self.step("删除假目标", lambda tid=target_id: self.ctx.client.request(
                "DELETE", f"/api/v2/fake-targets/{tid}", self.ctx.terminal(),
                expected=(200, 202, 204), headers={"Idempotency-Key": self.ctx.key("fake-del"),
                                                     "If-Match": '"2"'}))

    def run(self) -> None:
        self.runBothTargetKinds()


class SpecialProfileAcceptanceTest(Scenario):
    scenario_id = "AT-08"
    name = "ID/DECOMP 配置槽"

    def runProfileLifecycleAndCommands(self) -> None:
        draft_payload = self.ctx.require("at08Profile")
        created = self.step("创建特殊操作草稿", lambda: self.ctx.client.request(
            "POST", "/api/v2/special-operation-profiles", self.ctx.operations(),
            draft_payload, expected=(201,), headers={"Idempotency-Key": self.ctx.key("profile")}))
        profile_id = first_present(created.body, "id", "profileId")
        revision = first_present(created.body, "revision", default=1)
        if not profile_id:
            raise AcceptanceFailure("profile 创建响应缺少 id")
        published = self.step("发布 profile", lambda: self.ctx.client.request(
            "POST", f"/api/v2/special-operation-profiles/{profile_id}/actions/publish",
            self.ctx.operations(), {"profileRevision": revision}, expected=(200, 202),
            headers={"Idempotency-Key": self.ctx.key("profile-publish")}))
        if not first_present(published.body, "checksum", "profileChecksum"):
            raise AcceptanceFailure("发布响应缺少 checksum")
        self.step("执行 ID/DECOMP/CLR", lambda: self.command_sequence(
            "at08Commands", {"ID", "DECOMP"}))
        self.step("停用 profile", lambda: self.ctx.client.request(
            "POST", f"/api/v2/special-operation-profiles/{profile_id}/actions/retire",
            self.ctx.operations(), {"profileRevision": first_present(published.body, "revision",
                                                                       default=int(revision) + 1)},
            expected=(200, 202), headers={"Idempotency-Key": self.ctx.key("profile-retire")}))

    def run(self) -> None:
        self.runProfileLifecycleAndCommands()


class CollaborationAcceptanceTest(Scenario):
    scenario_id = "AT-09"
    name = "报告、脚本和消息"

    def runReportsScriptsMessages(self) -> None:
        script_payload = self.ctx.require("at09Script")
        message_payload = self.ctx.require("at09Message")
        script = self.step("写入仿真时间脚本", lambda: self.ctx.client.request(
            "POST", f"/api/v2/exercise-groups/{self.group_id()}/scripts",
            self.ctx.orchestrator(), script_payload, expected=(201,),
            headers={"Idempotency-Key": self.ctx.key("script")}))
        script_id = first_present(script.body, "id", "scriptItemId")
        self.step("查询脚本", lambda: self.ctx.client.request(
            "GET", f"/api/v2/exercise-groups/{self.group_id()}/scripts?pageSize=200",
            self.ctx.terminal(), expected=(200,)))
        self.step("确认脚本", lambda: self.ctx.client.request(
            "POST", f"/api/v2/scripts/{script_id}/actions/acknowledge", self.ctx.terminal(),
            {"scriptRevision": first_present(script.body, "revision", default=1)},
            expected=(200,), headers={"Idempotency-Key": self.ctx.key("script-ack")}))
        message = self.step("消息入站", lambda: self.ctx.client.request(
            "POST", f"/api/v2/exercise-groups/{self.group_id()}/messages",
            self.ctx.orchestrator(), message_payload, expected=(201,),
            headers={"Idempotency-Key": self.ctx.key("message")}))
        message_id = first_present(message.body, "id", "messageId")
        terminal_id = self.ctx.terminal().terminal_id
        self.step("查询消息", lambda: self.ctx.client.request(
            "GET", f"/api/v2/workstations/{terminal_id}/messages?pageSize=200",
            self.ctx.terminal(), expected=(200,)))
        self.step("消息已读", lambda: self.ctx.client.request(
            "POST", f"/api/v2/messages/{message_id}/actions/read", self.ctx.terminal(),
            {"messageRevision": first_present(message.body, "revision", default=1)},
            expected=(200,), headers={"Idempotency-Key": self.ctx.key("message-read")}))
        self.step("消息软删除", lambda: self.ctx.client.request(
            "DELETE", f"/api/v2/messages/{message_id}", self.ctx.terminal(), expected=(204,),
            headers={"Idempotency-Key": self.ctx.key("message-del"), "If-Match": '"2"'}))
        self.step("报告过滤", lambda: self.ctx.client.request(
            "GET", f"/api/v2/exercise-groups/{self.group_id()}/reports?reportKind=COMMAND&pageSize=200",
            self.ctx.terminal(), expected=(200,)))

    def run(self) -> None:
        self.runReportsScriptsMessages()


class DisplayAcceptanceTest(Scenario):
    scenario_id = "AT-10"
    name = "显示与屏幕方案"

    def runDisplayProfileScenario(self) -> None:
        terminal_id = self.ctx.terminal().terminal_id
        profile_payload = self.ctx.require("at10DisplayProfile")
        created = self.step("保存完整屏幕方案", lambda: self.ctx.client.request(
            "POST", f"/api/v2/workstations/{terminal_id}/display-profiles",
            self.ctx.terminal(), profile_payload, expected=(201,),
            headers={"Idempotency-Key": self.ctx.key("display")}))
        profile_id = first_present(created.body, "id", "profileId")
        revision = first_present(created.body, "revision", default=1)
        self.step("查询屏幕方案", lambda: self.ctx.client.request(
            "GET", f"/api/v2/workstations/{terminal_id}/display-profiles",
            self.ctx.terminal(), expected=(200,)))
        self.step("更新并调用屏幕方案", lambda: self.ctx.client.request(
            "PUT", f"/api/v2/display-profiles/{profile_id}", self.ctx.terminal(),
            profile_payload, expected=(200,),
            headers={"Idempotency-Key": self.ctx.key("display-put"),
                     "If-Match": f'"{revision}"'}))
        layout = self.ctx.require("at10LabelLayout")
        self.step("保存标牌布局", lambda: self.ctx.client.request(
            "PUT", f"/api/v2/workstations/{terminal_id}/aircraft/{self.aircraft_id()}/label-layout",
            self.ctx.terminal(), layout, expected=(200, 204),
            headers={"Idempotency-Key": self.ctx.key("layout"), "If-Match": '"1"'}))
        self.step("删除标牌布局", lambda: self.ctx.client.request(
            "DELETE", f"/api/v2/workstations/{terminal_id}/aircraft/{self.aircraft_id()}/label-layout",
            self.ctx.terminal(), expected=(204,),
            headers={"Idempotency-Key": self.ctx.key("layout-del"), "If-Match": '"2"'}))

    def run(self) -> None:
        self.runDisplayProfileScenario()


class RecoveryAcceptanceTest(Scenario):
    scenario_id = "AT-11"
    name = "重连与持久化"

    def runFailureInjectionMatrix(self) -> None:
        terminal = self.ctx.terminal()
        terminal_id = terminal.terminal_id
        first = self.step("首次 bootstrap", lambda: self.ctx.client.request(
            "GET", f"/api/v2/workstations/{terminal_id}/bootstrap", terminal, expected=(200,)))
        second = self.step("刷新后重新 bootstrap", lambda: self.ctx.client.request(
            "GET", f"/api/v2/workstations/{terminal_id}/bootstrap", terminal, expected=(200,)))
        first_seq = first_present(first.body, "snapshotSequence", "snapshot_sequence")
        second_seq = first_present(second.body, "snapshotSequence", "snapshot_sequence")
        if first_seq is None or second_seq is None or int(second_seq) < int(first_seq):
            raise AcceptanceFailure("bootstrap snapshotSequence 缺失或倒退")
        self.step("非法或过期 SSE 游标要求重新 bootstrap", lambda: self._expired_cursor(terminal))
        matrix = self.ctx.config.get("recoveryEvidence")
        if not isinstance(matrix, Mapping):
            raise AcceptanceBlocked(
                "未提供 recoveryEvidence；Java/Adapter 指令中、删除 Saga 中重启及 checksum 故障未执行")
        required = {"browserDisconnect", "javaRestart", "adapterRestart", "checksumFailure",
                    "recoveringToPaused", "upgradeRetention"}
        missing = sorted(key for key in required if not matrix.get(key))
        if missing:
            raise AcceptanceFailure(f"恢复故障矩阵缺少成功证据: {missing}")

    def run(self) -> None:
        self.runFailureInjectionMatrix()

    def _expired_cursor(self, terminal: Identity) -> None:
        query = urllib.parse.urlencode({"exerciseGroupId": self.group_id(),
                                        "terminalId": terminal.terminal_id})
        self.ctx.client.request(
            "GET", f"/api/v2/events?{query}", terminal, expected=(409,),
            headers={"Last-Event-ID": "expired.invalid.cursor"}, timeout_seconds=3)


class CapacityAcceptanceRunner(Scenario):
    scenario_id = "AT-12"
    name = "容量与稳定性"

    def runEightHourProfile(self) -> None:
        summary_path = self.ctx.config.get("capacitySummaryPath")
        if not summary_path:
            raise AcceptanceBlocked(
                "未提供 capacitySummaryPath；请先执行 loadtest/workstation_v2.js 与 sse_soak.py")
        path = Path(str(summary_path))
        if not path.is_file():
            raise AcceptanceBlocked(f"容量摘要不存在: {path}")
        summary = self.step("读取容量摘要", lambda: json.loads(path.read_text(encoding="utf-8")))
        self.step("校验 15.4 性能与稳定性门槛", lambda: self._assert_summary(summary))

    def run(self) -> None:
        self.runEightHourProfile()

    @staticmethod
    def _assert_summary(summary: Mapping[str, Any]) -> None:
        required_truthy = ("warmupCompleted", "steadyWindowCompleted", "eightHourCompleted",
                           "noProcessRestart", "noOom", "faultScheduleCompleted",
                           "reliableEventSideEffectsExactlyOnce")
        failed = [key for key in required_truthy if not summary.get(key)]
        success_samples = int(summary.get("successSamples", 0))
        if success_samples < 10000:
            failed.append(f"successSamples={success_samples}<10000")
        exact_capacity = {
            "parallelGroups": 8,
            "aircraftPerGroup": 200,
            "terminalsPerGroup": 8,
            "sseClients": 64,
            "getRequestsPerSecond": 100,
            "writeRequestsPerSecond": 20,
            "fullStateGroupsAtOneHz": 8,
        }
        for key, expected in exact_capacity.items():
            if int(summary.get(key, -1)) != expected:
                failed.append(f"{key}!={expected}")
        strict_upper_bounds = {
            "restSuccessP95Ms": 500,
            "adapterAckP95Ms": 200,
            "sseEndToEndP95Ms": 1000,
            "business5xxRate": 0.001,
        }
        upper_bounds = {
            "javaPeakHeapGiB": 6,
            "adapterPerGroupPeakRssGiB": 2,
            "archiveBytesPerAircraftFrame": 150,
        }
        for key, maximum in strict_upper_bounds.items():
            if float(summary.get(key, maximum)) >= maximum:
                failed.append(f"{key}>={maximum}")
        for key, maximum in upper_bounds.items():
            value = float(summary.get(key, maximum + 1))
            if value > maximum:
                failed.append(f"{key}>{maximum}")
        if float(summary.get("serverCpuFiveMinuteAverageMaxPct", 101)) > 70:
            failed.append("serverCpuFiveMinuteAverageMaxPct>70")
        if float(summary.get("serverCpuAnyContinuousFiveMinutesMaxPct", 101)) > 85:
            failed.append("serverCpuAnyContinuousFiveMinutesMaxPct>85")
        for key in ("javaHeapGrowthPct", "adapterRssGrowthPct", "sseQueueGrowthPct",
                    "dbConnectionGrowthPct"):
            if float(summary.get(key, 101)) > 10:
                failed.append(f"{key}>10")
        fault_counts = {"browserDisconnectCount": 20, "adapterReconnectCount": 8,
                        "javaControlledRestartCount": 2}
        for key, expected in fault_counts.items():
            if int(summary.get(key, -1)) != expected:
                failed.append(f"{key}!={expected}")
        write_mix = summary.get("writeMixPct", {})
        for key, expected in {"HDG": 40, "ALT": 20, "SPD": 20,
                              "RTE": 10, "HANDOVER": 10}.items():
            if float(write_mix.get(key, -1)) != expected:
                failed.append(f"writeMixPct.{key}!={expected}")
        if failed:
            raise AcceptanceFailure("AT-12 未达标: " + ", ".join(failed))


SCENARIOS = {
    cls.scenario_id: cls for cls in (
        CompleteFlightAcceptanceTest,
        MissedApproachAcceptanceTest,
        MultiTerminalHandoverAcceptanceTest,
        RouteConstraintAcceptanceTest,
        HoldingOrbitAcceptanceTest,
        TransponderAcceptanceTest,
        FakeTargetAcceptanceTest,
        SpecialProfileAcceptanceTest,
        CollaborationAcceptanceTest,
        DisplayAcceptanceTest,
        RecoveryAcceptanceTest,
        CapacityAcceptanceRunner,
    )
}


def load_config(path: Path) -> Dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"无法读取配置 {path}: {error}") from error
    if not isinstance(data, dict):
        raise SystemExit("配置根节点必须是 JSON 对象")
    return data


def merge_config(base: Mapping[str, Any], override: Mapping[str, Any]) -> Dict[str, Any]:
    """递归合并场景配置，避免 AT-03 等破坏性状态影响后续场景。"""
    merged = copy.deepcopy(dict(base))
    for key, value in override.items():
        if isinstance(value, Mapping) and isinstance(merged.get(key), Mapping):
            merged[key] = merge_config(merged[key], value)
        else:
            merged[key] = copy.deepcopy(value)
    return merged


def parse_scenarios(text: str) -> List[str]:
    if text.strip().lower() == "all":
        return list(SCENARIOS)
    selected = [item.strip().upper() for item in text.split(",") if item.strip()]
    unknown = sorted(set(selected) - set(SCENARIOS))
    if unknown:
        raise SystemExit(f"未知场景: {unknown}")
    return selected


def build_report(recorder: EvidenceRecorder, config: Mapping[str, Any],
                 scenarios: Sequence[ScenarioResult]) -> Dict[str, Any]:
    counts = {status: sum(result.status == status for result in scenarios)
              for status in (PASS, FAIL, BLOCKED)}
    overall = FAIL if counts[FAIL] else BLOCKED if counts[BLOCKED] else PASS
    return {
        "schemaVersion": "workbench-business-acceptance/1",
        "runId": recorder.run_id,
        "seed": recorder.seed,
        "startedAt": recorder.started_at,
        "finishedAt": utc_now(),
        "baseUrl": config.get("baseUrl"),
        "overallStatus": overall,
        "counts": counts,
        "requestIds": recorder.request_ids,
        "operationIds": recorder.operation_ids,
        "scenarios": [vars(result) | {"steps": [vars(step) for step in result.steps]}
                      for result in scenarios],
        "httpCalls": recorder.http_calls,
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="第二版 AT-01～AT-12 自动化业务验收")
    parser.add_argument("--config", type=Path,
                        default=Path(__file__).with_name("config.example.json"))
    parser.add_argument("--scenarios", default="AT-01,AT-02,AT-03,AT-04,AT-05,AT-06,AT-07,AT-08,AT-09,AT-10,AT-11",
                        help="逗号分隔的 AT 编号，或 all")
    parser.add_argument("--output", type=Path, help="结果 JSON；默认 artifacts/<runId>/result.json")
    parser.add_argument("--list", action="store_true", help="列出入口后退出")
    args = parser.parse_args(argv)
    if args.list:
        for scenario_id, scenario in SCENARIOS.items():
            methods = [name for name in dir(scenario) if re.match(r"run[A-Z]", name)]
            print(f"{scenario_id}\t{scenario.name}\t{methods[0] if methods else 'run'}")
        return 0

    config = load_config(args.config)
    seed = int(config.get("seed", 20260819))
    run_id = dt.datetime.now().strftime("%Y%m%d-%H%M%S") + f"-{seed}"
    recorder = EvidenceRecorder(run_id, seed)
    results: List[ScenarioResult] = []
    overrides = config.get("scenarioConfigs", {})
    if not isinstance(overrides, Mapping):
        raise SystemExit("scenarioConfigs 必须是按 AT 编号索引的对象")
    for scenario_id in parse_scenarios(args.scenarios):
        scenario_config = merge_config(config, overrides.get(scenario_id, {}))
        context = AcceptanceContext(scenario_config, recorder)
        scenario = SCENARIOS[scenario_id](context)
        print(f"[{scenario_id}] {scenario.name} ...", flush=True)
        result = scenario.execute()
        results.append(result)
        print(f"[{scenario_id}] {result.status}: {result.error or '完成'}", flush=True)

    report = build_report(recorder, config, results)
    output = args.output or Path(__file__).with_name("artifacts") / run_id / "result.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"验收证据: {output.resolve()}")
    if report["overallStatus"] == FAIL:
        return 1
    if report["overallStatus"] == BLOCKED:
        return 2
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("已中断", file=sys.stderr)
        raise SystemExit(130)
    except Exception:
        traceback.print_exc()
        raise SystemExit(3)
