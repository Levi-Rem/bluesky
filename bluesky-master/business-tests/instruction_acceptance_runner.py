#!/usr/bin/env python3
"""逐条执行详细设计第 7 章全部 30 个机长命令的业务验收。"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import importlib.util
import json
import math
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if not spec or not spec.loader:
        raise RuntimeError(f"无法加载 {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


BASE = _load("business_acceptance_base", HERE / "acceptance_runner.py")
CASE_MODULE = _load("instruction_case_catalog", HERE / "instruction_cases.py")
CASES: Dict[str, List[Dict[str, Any]]] = CASE_MODULE.CASES

QUEUE_COMMANDS = {
    "HDG", "LEFT", "RIGHT", "ALT", "VS", "SPD", "MACH", "DCT", "RTE", "RESUME",
    "ORBIT", "HOLD", "OFFSET", "VOR", "SIDSTAR", "P_LEVEL", "P_TIME", "TAKEOFF",
    "ILS", "MISSED", "NSPEED", "ID", "DECOMP",
}
GUIDANCE_COMMANDS = QUEUE_COMMANDS - {"ORBIT", "HOLD", "ID", "DECOMP"}

PASS, FAIL, BLOCKED = BASE.PASS, BASE.FAIL, BASE.BLOCKED
AcceptanceFailure = BASE.AcceptanceFailure
AcceptanceBlocked = BASE.AcceptanceBlocked


@dataclass
class CaseResult:
    command: str
    case_id: str
    status: str
    elapsed_ms: float
    message: str = ""
    instruction_id: Optional[str] = None
    request_ids: List[str] = field(default_factory=list)
    operation_ids: List[str] = field(default_factory=list)


@dataclass
class CommandResult:
    command: str
    status: str
    cases: List[CaseResult]
    passed: int
    failed: int
    blocked: int


class StrictFormat(dict):
    def __missing__(self, key: str) -> Any:
        raise AcceptanceBlocked(f"tokens 缺少模板字段 {key}")


def render(value: Any, tokens: Mapping[str, Any]) -> Any:
    if isinstance(value, str):
        return value.format_map(StrictFormat(tokens))
    if isinstance(value, Mapping):
        return {key: render(item, tokens) for key, item in value.items()}
    if isinstance(value, list):
        return [render(item, tokens) for item in value]
    return value


def field_of(value: Mapping[str, Any], *names: str, default: Any = None) -> Any:
    return BASE.first_present(value, *names, default=default)


def instruction_type(value: Mapping[str, Any]) -> str:
    return str(field_of(value, "instructionType", "instruction_type", "type", default=""))


def instruction_id(value: Mapping[str, Any]) -> Optional[str]:
    found = field_of(value, "instructionId", "instruction_id", "id")
    return str(found) if found else None


def parsed_payload(value: Mapping[str, Any]) -> Mapping[str, Any]:
    raw = field_of(value, "parsedPayload", "parsed_payload", "parameters", default={})
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError as error:
            raise AcceptanceFailure(f"parsedPayload 不是合法 JSON: {raw[:200]}") from error
    if not isinstance(raw, Mapping):
        raise AcceptanceFailure("parsedPayload 不是对象")
    return raw


def assert_subset(actual: Any, expected: Any, path: str = "parsedPayload") -> None:
    if isinstance(expected, Mapping) and "approx" in expected:
        try:
            number = float(actual)
        except (TypeError, ValueError) as error:
            raise AcceptanceFailure(f"{path} 不是数值: {actual!r}") from error
        tolerance = float(expected.get("tolerance", 1e-6))
        if not math.isclose(number, float(expected["approx"]), abs_tol=tolerance, rel_tol=0):
            raise AcceptanceFailure(
                f"{path} 期望约 {expected['approx']}±{tolerance}，实际 {number}")
        return
    if isinstance(expected, Mapping):
        if not isinstance(actual, Mapping):
            raise AcceptanceFailure(f"{path} 期望对象，实际 {actual!r}")
        for key, item in expected.items():
            if key not in actual:
                raise AcceptanceFailure(f"{path}.{key} 缺失")
            assert_subset(actual[key], item, f"{path}.{key}")
        return
    if isinstance(expected, list):
        if actual != expected:
            raise AcceptanceFailure(f"{path} 期望 {expected!r}，实际 {actual!r}")
        return
    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        if not math.isclose(float(actual), float(expected), abs_tol=1e-6, rel_tol=0):
            raise AcceptanceFailure(f"{path} 期望 {expected!r}，实际 {actual!r}")
        return
    if actual != expected:
        raise AcceptanceFailure(f"{path} 期望 {expected!r}，实际 {actual!r}")


class InstructionSuite:
    def __init__(self, config: Mapping[str, Any], recorder: Any) -> None:
        self.root_config = dict(config)
        self.recorder = recorder
        self.shared: Dict[str, Dict[str, Any]] = {}

    def _configuration(self, command: str, case: Mapping[str, Any]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
        fixtures = self.root_config.get("instructionFixtures", {})
        if not isinstance(fixtures, Mapping):
            raise AcceptanceBlocked("instructionFixtures 必须是对象")
        default_fixture = fixtures.get("default", {})
        command_fixture = BASE.merge_config(default_fixture, fixtures.get(command, {}))
        if not isinstance(command_fixture, Mapping):
            raise AcceptanceBlocked(f"instructionFixtures.{command} 必须是对象")
        config = BASE.merge_config(self.root_config, command_fixture)
        case_configs = command_fixture.get("caseConfigs", {})
        if isinstance(case_configs, Mapping):
            config = BASE.merge_config(config, case_configs.get(str(case["id"]), {}))
        tokens = dict(config.get("tokens", {}))
        tokens.update({key: value for key, value in config.items()
                       if isinstance(value, (str, int, float, bool))})
        for required in case.get("requires", []):
            value = config.get(required, tokens.get(required))
            if value in (None, "", False, [], {}) or _placeholder(value):
                raise AcceptanceBlocked(f"{command}/{case['id']} 缺少前置 {required}")
            tokens[required] = value
            if required.lower().endswith("aircraft") and isinstance(value, str):
                config["aircraftId"] = value
        for key in ("groupId", "aircraftId"):
            if _placeholder(config.get(key)):
                raise AcceptanceBlocked(f"{command}/{case['id']} 尚未替换配置 {key}")
        for identity_key in ("sourceTerminal",):
            identity = config.get(identity_key, {})
            if not isinstance(identity, Mapping) or any(
                    _placeholder(identity.get(name)) for name in ("terminalId", "fingerprintDigest")):
                raise AcceptanceBlocked(f"{command}/{case['id']} 尚未配置 {identity_key}")
        return config, tokens

    def run_command(self, command: str) -> CommandResult:
        outcomes: List[CaseResult] = []
        for case in CASES[command]:
            before_requests = len(self.recorder.request_ids)
            before_operations = len(self.recorder.operation_ids)
            started = time.perf_counter()
            try:
                config, tokens = self._configuration(command, case)
                context = BASE.AcceptanceContext(config, self.recorder)
                rendered = render(case, tokens)
                created_id = self._run_case(command, rendered, context, tokens)
                status, message = PASS, ""
            except AcceptanceBlocked as error:
                created_id, status, message = None, BLOCKED, str(error)
            except Exception as error:
                created_id, status, message = None, FAIL, str(error)
            outcomes.append(CaseResult(
                command, str(case["id"]), status,
                (time.perf_counter() - started) * 1000, message, created_id,
                self.recorder.request_ids[before_requests:],
                self.recorder.operation_ids[before_operations:]))
            print(f"  {status:7} {command}/{case['id']}"
                  + (f" - {message}" if message else ""), flush=True)

        if command not in ("ACID", "FRE", "DEL"):
            outcomes.extend(self._run_guards(command))
        failed = sum(item.status == FAIL for item in outcomes)
        blocked = sum(item.status == BLOCKED for item in outcomes)
        status = FAIL if failed else BLOCKED if blocked else PASS
        return CommandResult(command, status, outcomes,
                             sum(item.status == PASS for item in outcomes), failed, blocked)

    def _run_case(self, command: str, case: Mapping[str, Any], context: Any,
                  tokens: Mapping[str, Any]) -> Optional[str]:
        kind = str(case.get("kind"))
        if kind in ("INSTRUCTION", "STRUCTURED"):
            return self._run_instruction_case(command, case, context)
        if kind == "ACID":
            self._run_acid_case(case, context)
            return None
        if kind.startswith("FRE"):
            self._run_fre_case(case, context, foreign=kind == "FRE_FOREIGN")
            return None
        if kind.startswith("DEL_"):
            self._run_del_case(case, context)
            return None
        raise AcceptanceFailure(f"未知用例类型 {kind}")

    def _aircraft(self, context: Any, identity: Optional[Any] = None) -> Mapping[str, Any]:
        aircraft_id_value = str(context.require("aircraftId"))
        response = context.client.request(
            "GET", f"/api/v2/aircraft/{aircraft_id_value}",
            identity or context.terminal(), expected=(200,))
        if not isinstance(response.body, Mapping):
            raise AcceptanceFailure("航空器查询响应不是对象")
        return response.body

    def _revision(self, context: Any, identity: Optional[Any] = None) -> int:
        aircraft = self._aircraft(context, identity)
        value = field_of(aircraft, "revision", default=context.config.get("aircraftRevision"))
        if value is None:
            raise AcceptanceBlocked("航空器响应缺少 revision")
        return int(value)

    def _list_instructions(self, context: Any, identity: Optional[Any] = None) -> List[Any]:
        response = context.client.request(
            "GET", f"/api/v2/aircraft/{context.require('aircraftId')}/instructions",
            identity or context.terminal(), expected=(200,))
        items = BASE.deep_get(response.body, "items", default=[])
        if not isinstance(items, list):
            raise AcceptanceFailure("指令列表 items 不是数组")
        return items

    def _body(self, case: Mapping[str, Any], revision: int) -> Dict[str, Any]:
        body: Dict[str, Any] = {"aircraftRevision": revision,
                                "scheduling": case.get("scheduling", "REPLACE")}
        if case.get("command") is not None:
            body["command"] = case["command"]
        else:
            body["text"] = case.get("text")
        return body

    def _run_instruction_case(self, command: str, case: Mapping[str, Any],
                              context: Any) -> Optional[str]:
        terminal = context.terminal()
        before = self._aircraft(context) if case.get("assertNoBusinessSideEffect") else None
        count_before = len(self._list_instructions(context))
        revision = self._revision(context)
        body = self._body(case, revision)
        key = context.key(f"cmd-{command.lower()}-{case['id']}")
        response = context.client.request(
            "POST", f"/api/v2/aircraft/{context.require('aircraftId')}/instructions",
            terminal, body, expected=tuple(case["http"]),
            headers={"Idempotency-Key": key})
        expected_code = case.get("code")
        if expected_code and str(BASE.deep_get(response.body, "code", default="")) != expected_code:
            raise AcceptanceFailure(
                f"错误码期望 {expected_code}，实际 {BASE.deep_get(response.body, 'code', default='')}")

        success = 200 <= response.status < 300
        if not success:
            if case.get("assertNoInstructionCreated"):
                after_count = len(self._list_instructions(context))
                if after_count != count_before:
                    raise AcceptanceFailure("拒绝请求仍创建了指令记录")
            if before is not None:
                after = self._aircraft(context)
                for name in case.get("assertNoBusinessSideEffect", []):
                    if field_of(before, name, _snake(name)) != field_of(after, name, _snake(name)):
                        raise AcceptanceFailure(f"拒绝请求仍修改了业务字段 {name}")
            return None

        if not isinstance(response.body, Mapping):
            raise AcceptanceFailure("成功响应不是对象")
        if instruction_type(response.body) != command:
            raise AcceptanceFailure(
                f"指令类型期望 {command}，实际 {instruction_type(response.body)}")
        created_id = instruction_id(response.body)
        if not created_id:
            raise AcceptanceFailure("成功响应缺少 instruction id")
        assert_subset(parsed_payload(response.body), case.get("parsed", {}))

        if case.get("replay"):
            replay = context.client.request(
                "POST", f"/api/v2/aircraft/{context.require('aircraftId')}/instructions",
                terminal, body, expected=tuple(case["http"]), headers={"Idempotency-Key": key})
            if not isinstance(replay.body, Mapping) or instruction_id(replay.body) != created_id:
                raise AcceptanceFailure("同幂等键重放没有返回原指令")
            if len(self._list_instructions(context)) != count_before + 1:
                raise AcceptanceFailure("幂等重放创建了重复指令")
            changed = copy.deepcopy(body)
            changed["scheduling"] = "AFTER_COMPLETION" if body["scheduling"] == "REPLACE" else "REPLACE"
            conflict = context.client.request(
                "POST", f"/api/v2/aircraft/{context.require('aircraftId')}/instructions",
                terminal, changed, expected=(409,), headers={"Idempotency-Key": key})
            if BASE.deep_get(conflict.body, "code", default="") != "IDEMPOTENCY_KEY_REUSED":
                raise AcceptanceFailure("同幂等键不同请求未返回 IDEMPOTENCY_KEY_REUSED")

        observed = set(case.get("observedStatuses", []))
        if observed:
            final = self._await_status(context, created_id, observed)
            self._assert_reports(command, case, context, created_id, final)
        return created_id

    def _assert_reports(self, command: str, case: Mapping[str, Any], context: Any,
                        created_id: str, final: Mapping[str, Any]) -> None:
        query = (f"/api/v2/exercise-groups/{context.require('groupId')}/reports"
                 f"?reportKind=COMMAND&aircraftId={context.require('aircraftId')}&pageSize=200")
        command_response = context.client.request("GET", query, context.terminal(), expected=(200,))
        items = BASE.deep_get(command_response.body, "items", default=[])
        report = next((item for item in items if created_id in BASE.collect_named_values(
            item, ("instructionId", "instruction_id", "sourceInstructionId"))), None)
        if not isinstance(report, Mapping):
            raise AcceptanceFailure(f"命令报告中找不到指令 {created_id}")
        if case.get("text") is not None:
            raw = field_of(report, "rawText", "raw_text", "originalText")
            normalized = field_of(report, "normalizedCommand", "normalizedText",
                                  "normalized_text")
            expected_raw = str(case["text"]).strip()
            expected_normalized = field_of(parsed_payload(final), "normalizedText",
                                           default=expected_raw.upper())
            if str(raw).strip() != expected_raw:
                raise AcceptanceFailure(f"命令报告原始文本不符: {raw!r} != {expected_raw!r}")
            if str(normalized).strip() != str(expected_normalized).strip():
                raise AcceptanceFailure(
                    f"命令报告规范文本不符: {normalized!r} != {expected_normalized!r}")
        if command in GUIDANCE_COMMANDS and str(field_of(final, "status", default="")) == "COMPLETED":
            flight_query = (f"/api/v2/exercise-groups/{context.require('groupId')}/reports"
                            f"?reportKind=FLIGHT&aircraftId={context.require('aircraftId')}"
                            "&eventType=TARGET_REACHED&pageSize=200")
            flight_response = context.client.request(
                "GET", flight_query, context.terminal(), expected=(200,))
            flight_items = BASE.deep_get(flight_response.body, "items", default=[])
            linked = any(
                created_id in " ".join(str(item.get(field) or "")
                                       for field in ("instructionId", "instruction_id",
                                                     "sourceInstructionId", "sourceEventId"))
                for item in flight_items)
            if not linked:
                raise AcceptanceFailure("完成指令缺少关联的 TARGET_REACHED 飞行报告")

    def _await_status(self, context: Any, created_id: str,
                      expected: Iterable[str]) -> Mapping[str, Any]:
        expected_set = set(expected)
        timeout = float(context.config.get("instructionTimeoutSeconds", 120))
        deadline = time.monotonic() + timeout
        last: Mapping[str, Any] = {}
        while time.monotonic() < deadline:
            response = context.client.request(
                "GET", f"/api/v2/instructions/{created_id}", context.terminal(), expected=(200,))
            if isinstance(response.body, Mapping):
                last = response.body
                status = str(field_of(last, "status", default=""))
                if status in expected_set:
                    return last
                if status in BASE.TERMINAL_INSTRUCTION_STATES:
                    raise AcceptanceFailure(
                        f"指令到达非预期终态 {status}，期望 {sorted(expected_set)}")
            time.sleep(float(context.config.get("pollIntervalSeconds", 0.5)))
        raise AcceptanceFailure(
            f"指令 {created_id} 超时，最后状态 {field_of(last, 'status', default='UNKNOWN')}")

    def _run_acid_case(self, case: Mapping[str, Any], context: Any) -> None:
        suffix = str(case["suffix"]).upper()
        response = context.client.request(
            "GET", f"/api/v2/exercise-groups/{context.require('groupId')}/aircraft",
            context.terminal(), expected=(200,))
        items = BASE.deep_get(response.body, "items", default=[])
        callsigns = [str(field_of(item, "callsign", default="")).upper() for item in items]
        matches = [callsign for callsign in callsigns if callsign.endswith(suffix)]
        outcome = str(case["outcome"])
        if outcome == "UNIQUE" and len(matches) != 1:
            raise AcceptanceFailure(f"ACID 唯一匹配期望 1 架，实际 {matches}")
        if outcome == "AMBIGUOUS" and len(matches) < 2:
            raise AcceptanceFailure(f"ACID 多匹配期望至少 2 架，实际 {matches}")
        if outcome == "NOT_FOUND" and matches:
            raise AcceptanceFailure(f"ACID 不应匹配，实际 {matches}")
        if outcome == "INVALID" and len(suffix) in (3, 4):
            raise AcceptanceFailure("ACID 非法后缀夹具本身合法")
        evidence_path = context.config.get("clientAutomationEvidencePath")
        if not evidence_path:
            raise AcceptanceBlocked("ACID 是前端选择行为，缺少 clientAutomationEvidencePath")
        evidence = json.loads(Path(str(evidence_path)).read_text(encoding="utf-8"))
        evidence_result = BASE.deep_get(evidence, "ACID", str(case["id"]), default="")
        if evidence_result == BLOCKED:
            raise AcceptanceBlocked("ACID 前端自动化证据尚未执行")
        if evidence_result != PASS:
            raise AcceptanceFailure("前端自动化证据未证明 ACID 选机/保持选择行为")

    def _run_fre_case(self, case: Mapping[str, Any], context: Any, foreign: bool) -> None:
        kind = str(case["kind"])
        identity = context.identity("foreignTerminal", "TERMINAL") if foreign else context.terminal()
        before_count = len(self._list_instructions(context, identity if not foreign else context.terminal()))
        revision = self._revision(context, identity)
        if kind == "FRE_STALE_REVISION":
            revision = revision - 1 if revision > 0 else revision + 1000
        body: Dict[str, Any] = {"targetFrequencyMhz": float(case["targetFrequency"])}
        if kind != "FRE_MISSING_REVISION":
            body["aircraftRevision"] = revision
        key = context.key(f"fre-{case['id']}")
        headers = {} if kind == "FRE_MISSING_IDEMPOTENCY" else {"Idempotency-Key": key}
        response = context.client.request(
            "POST", f"/api/v2/aircraft/{context.require('aircraftId')}/handover", identity,
            body, expected=tuple(case["http"]), headers=headers)
        if case.get("code") and BASE.deep_get(response.body, "code", default="") != case["code"]:
            raise AcceptanceFailure(f"FRE 错误码不符: {response.body}")
        after_count = len(self._list_instructions(context, context.terminal()))
        if after_count != before_count:
            raise AcceptanceFailure("FRE 错误创建了飞行指令")
        if response.status == 200:
            target = context.identity("targetTerminal", "TERMINAL")
            if str(BASE.deep_get(response.body, "targetTerminalId", default="")) != target.terminal_id:
                raise AcceptanceFailure("FRE 目标终端与频率解析结果不符")
            if case.get("replay"):
                replay = context.client.request(
                    "POST", f"/api/v2/aircraft/{context.require('aircraftId')}/handover",
                    identity, body, expected=(200,), headers={"Idempotency-Key": key})
                if replay.body != response.body:
                    raise AcceptanceFailure("FRE 同幂等键重放未返回原移交结果")
                changed = dict(body)
                changed["targetFrequencyMhz"] = float(case["targetFrequency"]) + 0.001
                conflict = context.client.request(
                    "POST", f"/api/v2/aircraft/{context.require('aircraftId')}/handover",
                    identity, changed, expected=(409,), headers={"Idempotency-Key": key})
                if BASE.deep_get(conflict.body, "code", default="") != "IDEMPOTENCY_KEY_REUSED":
                    raise AcceptanceFailure("FRE 同键异载荷未返回 IDEMPOTENCY_KEY_REUSED")

    def _run_del_case(self, case: Mapping[str, Any], context: Any) -> None:
        command_state = self.shared.setdefault("DEL", {})
        aircraft_id_value = str(context.require("aircraftId"))
        terminal = context.terminal()
        kind = str(case["kind"])
        if kind == "DEL_PREVIEW":
            response = self._deletion_preview(context, aircraft_id_value, "del-preview")
            required = ("aircraftId", "callsign", "responsibleTerminalId",
                        "activeInstructionCount", "lifecycle", "latitude", "longitude",
                        "confirmationRequired", "confirmationToken")
            missing = [key for key in required if BASE.deep_get(response.body, key) is None]
            if missing:
                raise AcceptanceFailure(f"删除预览缺少字段 {missing}")
            if BASE.deep_get(response.body, "confirmationRequired") is not True:
                raise AcceptanceFailure("删除预览必须明确 confirmationRequired=true")
            command_state["token"] = response.body["confirmationToken"]
            return
        if kind == "DEL_WITHOUT_TOKEN":
            response = context.client.request(
                "DELETE", f"/api/v2/aircraft/{aircraft_id_value}", terminal,
                expected=tuple(case["http"]), headers={"Idempotency-Key": context.key("del")})
            if case.get("code") and BASE.deep_get(response.body, "code", default="") != case["code"]:
                raise AcceptanceFailure("缺少删除 token 时的错误码不正确")
            return
        token = command_state.get("token")
        if not token:
            raise AcceptanceBlocked("删除确认用例缺少同轮 preview token")
        if kind == "DEL_FOREIGN_TOKEN":
            foreign_identity = context.identity("foreignTerminal", "TERMINAL")
            response = context.client.request(
                "DELETE", f"/api/v2/aircraft/{aircraft_id_value}", foreign_identity,
                expected=tuple(case["http"]), headers={"Idempotency-Key": context.key("del-foreign"),
                                                       "X-Confirmation-Token": token})
            if case.get("code") and BASE.deep_get(response.body, "code", default="") != case["code"]:
                raise AcceptanceFailure("其他终端使用 token 的错误码不正确")
            return
        if kind == "DEL_TAMPERED_TOKEN":
            replacement = "A" if token[-1:] != "A" else "B"
            self._assert_confirmation_rejected(
                context, aircraft_id_value, token[:-1] + replacement, case, "del-tampered")
            return
        if kind == "DEL_OTHER_AIRCRAFT_TOKEN":
            self._assert_confirmation_rejected(
                context, str(context.require("secondaryAircraftId")), token,
                case, "del-other-aircraft")
            return
        if kind == "DEL_REVISION_CHANGED_TOKEN":
            preview = self._deletion_preview(
                context, aircraft_id_value, f"del-revision-preview-{case['id']}")
            bound_token = str(BASE.deep_get(preview.body, "confirmationToken", default=""))
            old_revision = self._revision(context)
            instruction = context.client.request(
                "POST", f"/api/v2/aircraft/{aircraft_id_value}/instructions", terminal,
                {"aircraftRevision": old_revision, "scheduling": "REPLACE", "text": "IDENT"},
                expected=(202,), headers={"Idempotency-Key": context.key("del-revision-ident")})
            if not instruction_id(instruction.body):
                raise AcceptanceFailure("用于触发版本变化的 IDENT 响应缺少 instruction id")
            deadline = time.monotonic() + float(context.config.get("instructionTimeoutSeconds", 120))
            while time.monotonic() < deadline and self._revision(context) == old_revision:
                time.sleep(float(context.config.get("pollIntervalSeconds", 0.5)))
            if self._revision(context) == old_revision:
                raise AcceptanceFailure("IDENT 执行后航空器 revision 未变化，无法证明 token 版本绑定")
            self._assert_confirmation_rejected(
                context, aircraft_id_value, bound_token, case, "del-revision-changed")
            return
        if kind == "DEL_EXPIRED_TOKEN":
            preview = self._deletion_preview(
                context, aircraft_id_value, f"del-expiry-preview-{case['id']}")
            bound_token = str(BASE.deep_get(preview.body, "confirmationToken", default=""))
            ttl = int(context.config.get("deletionTokenTtlSeconds", 30))
            time.sleep(ttl + 2)
            self._assert_confirmation_rejected(
                context, aircraft_id_value, bound_token, case, "del-expired")
            return
        if kind == "DEL_MISSING_IDEMPOTENCY":
            preview = self._deletion_preview(
                context, aircraft_id_value, f"del-no-idem-preview-{case['id']}")
            bound_token = str(BASE.deep_get(preview.body, "confirmationToken", default=""))
            response = context.client.request(
                "DELETE", f"/api/v2/aircraft/{aircraft_id_value}", terminal,
                expected=tuple(case["http"]), headers={"X-Confirmation-Token": bound_token})
            if BASE.deep_get(response.body, "code", default="") != case.get("code"):
                raise AcceptanceFailure("DEL 缺少 Idempotency-Key 的错误码不正确")
            return
        if kind == "DEL_CONFIRM":
            # Earlier negative cases intentionally wait past the 30-second TTL; a real UI
            # must re-preview rather than reuse the stale token before final confirmation.
            preview = self._deletion_preview(
                context, aircraft_id_value, f"del-confirm-preview-{case['id']}")
            token = str(BASE.deep_get(preview.body, "confirmationToken", default=""))
            key = context.key("del-confirm")
            response = context.client.request(
                "DELETE", f"/api/v2/aircraft/{aircraft_id_value}", terminal,
                expected=tuple(case["http"]), headers={"Idempotency-Key": key,
                                                       "X-Confirmation-Token": token})
            operation_id = field_of(response.body, "operationId", "operation_id")
            if not operation_id:
                raise AcceptanceFailure("删除确认响应缺少 operationId")
            idempotent_replay = context.client.request(
                "DELETE", f"/api/v2/aircraft/{aircraft_id_value}", terminal,
                expected=tuple(case["http"]), headers={"Idempotency-Key": key,
                                                       "X-Confirmation-Token": token})
            if field_of(idempotent_replay.body, "operationId", "operation_id") != operation_id:
                raise AcceptanceFailure("DEL 同幂等键重放未返回原 operationId")
            consumed_replay = context.client.request(
                "DELETE", f"/api/v2/aircraft/{aircraft_id_value}", terminal,
                expected=(428,), headers={"Idempotency-Key": context.key("del-replay"),
                                         "X-Confirmation-Token": token})
            if BASE.deep_get(consumed_replay.body, "code", default="") != "CONFIRMATION_REQUIRED":
                raise AcceptanceFailure("删除 token 重放未被 CONFIRMATION_REQUIRED 拒绝")
            return
        raise AcceptanceFailure(f"未知 DEL 子流程 {kind}")

    def _deletion_preview(self, context: Any, aircraft_id_value: str, key_suffix: str) -> Any:
        revision = self._revision(context)
        response = context.client.request(
            "POST", f"/api/v2/aircraft/{aircraft_id_value}/deletion-preview",
            context.terminal(), {"aircraftRevision": revision}, expected=(200,),
            headers={"Idempotency-Key": context.key(key_suffix),
                     "If-Match": f'"{revision}"'})
        if not BASE.deep_get(response.body, "confirmationToken", default=""):
            raise AcceptanceFailure("删除预览响应缺少 confirmationToken")
        return response

    def _assert_confirmation_rejected(self, context: Any, aircraft_id_value: str,
                                      token: str, case: Mapping[str, Any],
                                      key_suffix: str) -> None:
        response = context.client.request(
            "DELETE", f"/api/v2/aircraft/{aircraft_id_value}", context.terminal(),
            expected=tuple(case["http"]),
            headers={"Idempotency-Key": context.key(key_suffix),
                     "X-Confirmation-Token": token})
        expected_code = case.get("code")
        if expected_code and BASE.deep_get(response.body, "code", default="") != expected_code:
            raise AcceptanceFailure(
                f"删除确认拒绝错误码期望 {expected_code}，实际 {response.body}")

    def _run_guards(self, command: str) -> List[CaseResult]:
        results: List[CaseResult] = []
        fixtures = self.root_config.get("instructionFixtures", {})
        fixture = BASE.merge_config(fixtures.get("default", {}), fixtures.get(command, {}))
        guard_aircraft = fixture.get("guardAircraftId") if isinstance(fixture, Mapping) else None
        positive = next((case for case in CASES[command]
                         if case.get("kind") in ("INSTRUCTION", "STRUCTURED")
                         and any(200 <= int(code) < 300 for code in case.get("http", []))), None)
        if not guard_aircraft or _placeholder(guard_aircraft) or positive is None:
            return [CaseResult(command, "__common-guards__", BLOCKED, 0,
                               f"instructionFixtures.{command}.guardAircraftId 或正向用例缺失")]
        guard_case = copy.deepcopy(positive)
        guard_case["requires"] = []
        config = BASE.merge_config(self.root_config, fixture)
        config["aircraftId"] = guard_aircraft
        tokens = dict(config.get("tokens", {}))
        try:
            rendered = render(guard_case, tokens)
        except Exception as error:
            return [CaseResult(command, "__common-guards__", BLOCKED, 0, str(error))]
        checks = (
            ("missing-revision-428", self._guard_missing_revision),
            ("stale-revision-409", self._guard_stale_revision),
            ("text-command-exclusive", self._guard_both_modes),
            ("foreign-terminal-403", self._guard_foreign),
            ("anonymous-terminal-403", self._guard_anonymous),
        )
        if command in QUEUE_COMMANDS:
            checks += (("after-completion-queue", self._guard_after_completion),)
        for case_id, function in checks:
            before_requests = len(self.recorder.request_ids)
            started = time.perf_counter()
            try:
                context = BASE.AcceptanceContext(config, self.recorder)
                function(command, rendered, context)
                status, message = PASS, ""
            except AcceptanceBlocked as error:
                status, message = BLOCKED, str(error)
            except Exception as error:
                status, message = FAIL, str(error)
            result = CaseResult(command, case_id, status,
                                (time.perf_counter() - started) * 1000, message,
                                request_ids=self.recorder.request_ids[before_requests:])
            print(f"  {status:7} {command}/{case_id}" + (f" - {message}" if message else ""),
                  flush=True)
            results.append(result)
        return results

    def _guard_request(self, command: str, case: Mapping[str, Any], context: Any,
                       body: Mapping[str, Any], expected: Sequence[int], expected_code: str,
                       identity: Optional[Any] = None) -> None:
        count = len(self._list_instructions(context))
        response = context.client.request(
            "POST", f"/api/v2/aircraft/{context.require('aircraftId')}/instructions",
            identity or context.terminal(), body, expected=tuple(expected),
            headers={"Idempotency-Key": context.key(f"guard-{command}")})
        if BASE.deep_get(response.body, "code", default="") != expected_code:
            raise AcceptanceFailure(f"期望 {expected_code}，实际 {response.body}")
        if len(self._list_instructions(context)) != count:
            raise AcceptanceFailure("通用守卫拒绝请求仍创建了指令")

    def _valid_body(self, case: Mapping[str, Any], revision: int) -> Dict[str, Any]:
        return self._body(case, revision)

    def _guard_missing_revision(self, command: str, case: Mapping[str, Any], context: Any) -> None:
        body = self._valid_body(case, 1)
        body.pop("aircraftRevision")
        self._guard_request(command, case, context, body, (428,), "REVISION_REQUIRED")

    def _guard_stale_revision(self, command: str, case: Mapping[str, Any], context: Any) -> None:
        body = self._valid_body(case, self._revision(context) + 999)
        self._guard_request(command, case, context, body, (409,), "REVISION_CONFLICT")

    def _guard_both_modes(self, command: str, case: Mapping[str, Any], context: Any) -> None:
        body = self._valid_body(case, self._revision(context))
        if "text" in body:
            body["command"] = {"type": command, "parameters": {}}
        else:
            body["text"] = command
        self._guard_request(command, case, context, body, (400,), "INVALID_INSTRUCTION")

    def _guard_foreign(self, command: str, case: Mapping[str, Any], context: Any) -> None:
        foreign = context.identity("foreignTerminal", "TERMINAL")
        body = self._valid_body(case, self._revision(context))
        self._guard_request(command, case, context, body, (403,), "AIRCRAFT_NOT_ASSIGNED", foreign)

    def _guard_anonymous(self, command: str, case: Mapping[str, Any], context: Any) -> None:
        body = self._valid_body(case, self._revision(context))
        path = f"/api/v2/aircraft/{context.require('aircraftId')}/instructions"
        request_id = f"bat-{self.recorder.run_id}-anonymous-{len(self.recorder.http_calls) + 1}"
        raw_body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            context.client.base_url + path, data=raw_body, method="POST",
            headers={"Content-Type": "application/json", "X-Request-Id": request_id,
                     "Idempotency-Key": context.key(f"anonymous-{command}")})
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=context.client.timeout_seconds) as response:
                status, response_headers, raw = response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            status, response_headers, raw = error.code, dict(error.headers), error.read()
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise AcceptanceBlocked(f"匿名身份检查无法访问服务: {error}") from error
        try:
            response_body = json.loads(raw.decode("utf-8")) if raw else None
        except json.JSONDecodeError:
            response_body = raw.decode("utf-8", errors="replace")
        result = BASE.HttpResult(
            "POST", path, status,
            {key.lower(): value for key, value in response_headers.items()}, response_body,
            (time.perf_counter() - started) * 1000)
        self.recorder.record_http(result, request_id, body)
        if status != 403 or BASE.deep_get(
                response_body, "code", default="") != "TRUSTED_IDENTITY_REJECTED":
            raise AcceptanceFailure(
                f"匿名指令应返回 403 TRUSTED_IDENTITY_REJECTED，实际 {status} {response_body}")

    def _guard_after_completion(self, command: str, case: Mapping[str, Any],
                                context: Any) -> None:
        queue_aircraft = context.config.get("queueAircraftId")
        if not queue_aircraft or _placeholder(queue_aircraft):
            raise AcceptanceBlocked(f"instructionFixtures.{command}.queueAircraftId 未配置")
        queue_config = BASE.merge_config(context.config, {"aircraftId": queue_aircraft})
        queue_context = BASE.AcceptanceContext(queue_config, self.recorder)
        revision = self._revision(queue_context)
        first_body = self._valid_body(case, revision)
        first_body["scheduling"] = "REPLACE"
        first = queue_context.client.request(
            "POST", f"/api/v2/aircraft/{queue_aircraft}/instructions",
            queue_context.terminal(), first_body, expected=(202,),
            headers={"Idempotency-Key": queue_context.key(f"queue-first-{command}")})
        first_id = instruction_id(first.body) if isinstance(first.body, Mapping) else None
        if not first_id:
            raise AcceptanceFailure("队列前置指令缺少 id")
        second_body = self._valid_body(case, revision)
        second_body["scheduling"] = "AFTER_COMPLETION"
        second = queue_context.client.request(
            "POST", f"/api/v2/aircraft/{queue_aircraft}/instructions",
            queue_context.terminal(), second_body, expected=(202,),
            headers={"Idempotency-Key": queue_context.key(f"queue-second-{command}")})
        if not isinstance(second.body, Mapping):
            raise AcceptanceFailure("AFTER_COMPLETION 响应不是对象")
        status = str(field_of(second.body, "status", default=""))
        predecessor = field_of(second.body, "predecessorId", "predecessor_id")
        reasons = second.body.get("blockingReasons", [])
        if status != "BLOCKED" or str(predecessor) != first_id \
                or "PREDECESSOR_ACTIVE" not in reasons:
            raise AcceptanceFailure(
                f"AFTER_COMPLETION 未正确排队: status={status}, "
                f"predecessor={predecessor}, reasons={reasons}")


def _snake(name: str) -> str:
    chars: List[str] = []
    for char in name:
        if char.isupper():
            chars.extend(("_", char.lower()))
        else:
            chars.append(char)
    return "".join(chars)


def _placeholder(value: Any) -> bool:
    return isinstance(value, str) and value.startswith("REPLACE_WITH_")


def load_json(path: Path) -> Dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise SystemExit(f"{path} 根节点必须是对象")
    return value


def verify_catalog(catalog_path: Path) -> None:
    catalog = load_json(catalog_path)
    types = [str(item["type"]) for item in catalog.get("commands", [])]
    case_types = list(CASES)
    if types != case_types:
        missing = sorted(set(types) - set(case_types))
        extra = sorted(set(case_types) - set(types))
        raise SystemExit(f"命令目录与业务用例不一致，缺少={missing}，多余={extra}，顺序也必须一致")
    empty = [command for command, cases in CASES.items() if not cases]
    duplicate_ids = {command: _duplicates(str(case["id"]) for case in cases)
                     for command, cases in CASES.items()}
    duplicate_ids = {key: value for key, value in duplicate_ids.items() if value}
    if empty or duplicate_ids:
        raise SystemExit(f"用例目录非法：empty={empty}, duplicateIds={duplicate_ids}")


def _duplicates(values: Iterable[str]) -> List[str]:
    seen, duplicates = set(), []
    for value in values:
        if value in seen and value not in duplicates:
            duplicates.append(value)
        seen.add(value)
    return duplicates


def selected_commands(text: str) -> List[str]:
    if text.strip().lower() == "all":
        return list(CASES)
    selected = [item.strip().upper() for item in text.split(",") if item.strip()]
    unknown = sorted(set(selected) - set(CASES))
    if unknown:
        raise SystemExit(f"未知命令: {unknown}")
    return selected


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="逐机长指令自动化业务测试")
    parser.add_argument("--config", type=Path,
                        default=HERE / "instruction-config.example.json")
    parser.add_argument("--commands", default="all", help="逗号分隔命令，或 all")
    parser.add_argument("--catalog", type=Path,
                        default=ROOT / "docs/contracts/command-catalog-v2.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args(argv)
    verify_catalog(args.catalog)
    if args.list:
        for command, cases in CASES.items():
            print(f"{command:10} {len(cases):3} cases  "
                  + ", ".join(str(case["id"]) for case in cases))
        explicit = sum(len(cases) for cases in CASES.values())
        generated = (len(CASES) - 3) * 5 + len(QUEUE_COMMANDS)
        print(f"EXPLICIT {explicit} cases / {len(CASES)} commands")
        print(f"GENERATED {generated} common guards")
        print(f"TOTAL {explicit + generated} checks")
        return 0

    config = load_json(args.config)
    seed = int(config.get("seed", 20260819))
    run_id = dt.datetime.now().strftime("%Y%m%d-%H%M%S") + f"-instructions-{seed}"
    recorder = BASE.EvidenceRecorder(run_id, seed)
    suite = InstructionSuite(config, recorder)
    commands: List[CommandResult] = []
    for command in selected_commands(args.commands):
        print(f"[{command}] {len(CASES[command])} 个业务用例", flush=True)
        commands.append(suite.run_command(command))
    counts = {status: sum(result.status == status for result in commands)
              for status in (PASS, FAIL, BLOCKED)}
    overall = FAIL if counts[FAIL] else BLOCKED if counts[BLOCKED] else PASS
    report = {
        "schemaVersion": "workbench-instruction-acceptance/1",
        "runId": run_id,
        "seed": seed,
        "startedAt": recorder.started_at,
        "finishedAt": BASE.utc_now(),
        "overallStatus": overall,
        "commandCounts": counts,
        "caseCounts": {status: sum(item.status == status for result in commands
                                    for item in result.cases)
                       for status in (PASS, FAIL, BLOCKED)},
        "catalogCommandCount": len(CASES),
        "catalogCaseCount": sum(len(cases) for cases in CASES.values()),
        "generatedGuardCount": (len(CASES) - 3) * 5 + len(QUEUE_COMMANDS),
        "commands": [asdict(result) for result in commands],
        "requestIds": recorder.request_ids,
        "operationIds": recorder.operation_ids,
        "httpCalls": recorder.http_calls,
    }
    output = args.output or HERE / "artifacts" / run_id / "instruction-result.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"逐指令验收结果: {output.resolve()}")
    return 1 if overall == FAIL else 2 if overall == BLOCKED else 0


if __name__ == "__main__":
    raise SystemExit(main())
