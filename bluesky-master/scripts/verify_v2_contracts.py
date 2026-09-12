#!/usr/bin/env python3
"""P00：第二版契约跨语言校验脚本（CI 执行入口）。

权威来源：《模拟飞行员工作台第二版详细设计》2.2。
校验四类机器可读契约 + Java/Python 共享协议向量：
- load_documents()          加载 openapi/sse/adapter/catalog/vectors
- validate_json_schemas()   结构性校验（必需键、枚举存在）
- compare_enum_sets()       OpenAPI ↔ 命令目录 ↔ 详细设计固定枚举比对
- validate_vectors()        与 Java 侧同一规则校验共享协议向量
- main()                    聚合执行；--vectors 只跑向量校验

仅依赖标准库；PyYAML 存在时用于解析 openapi-v2.yaml。
"""

import argparse
import json
import pathlib
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - 环境缺少 PyYAML 时给出明确错误
    yaml = None

CONTRACTS_DIR = pathlib.Path(__file__).resolve().parent.parent / "docs" / "contracts"

EXERCISE_STATES = [
    "READY", "STARTING", "RUNNING", "PAUSING", "PAUSED",
    "RESUMING", "RECOVERING", "RECOVERY_FAILED", "ENDING", "ENDED",
]
AIRCRAFT_LIFECYCLE = [
    "PLANNED", "CREATE_REQUESTED", "CREATE_FAILED", "ACTIVE",
    "DELETE_REQUESTED", "DELETE_FAILED", "DELETED",
]
INSTRUCTION_STATES = [
    "RECEIVED", "VALIDATED", "BLOCKED", "DISPATCHING", "EXECUTING",
    "COMPLETED", "REPLACED", "FAILED", "TIMED_OUT", "CANCELLED", "REJECTED",
]
ERROR_CODES = [
    "TERMINAL_NOT_FOUND", "TERMINAL_NOT_IN_GROUP", "TRUSTED_IDENTITY_REJECTED",
    "TERMINAL_FREQUENCY_IN_USE", "AIRCRAFT_NOT_ASSIGNED",
    "AIRCRAFT_ASSIGNMENT_CHANGED", "REVISION_REQUIRED", "REVISION_CONFLICT",
    "IDEMPOTENCY_KEY_REUSED", "TRAINING_STATE_INVALID",
    "CHANNEL_DISPATCH_IN_PROGRESS", "INVALID_INSTRUCTION",
    "INITIAL_STATE_INCOMPLETE", "DUPLICATE_CALLSIGN", "CONFIRMATION_REQUIRED",
    "PERFORMANCE_LIMIT_EXCEEDED", "REFERENCE_NOT_FOUND",
    "PROCEDURE_STATE_INVALID", "FEATURE_PROFILE_NOT_CONFIGURED",
    "PROFILE_IMMUTABLE", "PROFILE_NOT_LOADED", "ADAPTER_ACK_TIMEOUT",
    "GUIDANCE_FAILED", "EVENT_CURSOR_EXPIRED", "ENGINE_RECOVERING",
    "DELETE_RECONCILIATION_REQUIRED",
]
SSE_REQUIRED_FIELDS = [
    "eventId", "streamEpoch", "deliverySequence", "groupSequence",
    "eventType", "exerciseGroupId", "terminalId", "systemTimeUtc",
    "simulationTimeSeconds", "payload",
]
CONTROL_CHANNELS = ["LATERAL", "VERTICAL", "SPEED", "BUSINESS_FIELD", "SPECIAL"]


def load_documents(contracts_dir=None):
    """加载全部契约文档；缺失或不可解析立即抛错。"""
    base = pathlib.Path(contracts_dir) if contracts_dir else CONTRACTS_DIR
    documents = {"base_dir": base}

    openapi_path = base / "openapi-v2.yaml"
    if yaml is None:
        raise RuntimeError("缺少 PyYAML，无法解析 " + str(openapi_path))
    with openapi_path.open("r", encoding="utf-8") as handle:
        documents["openapi"] = yaml.safe_load(handle)

    json_files = {
        "sse-event-v2.schema.json": "sse-event-v2",
        "adapter-protocol-v2.schema.json": "adapter-protocol-v2",
        "command-catalog-v2.json": "command-catalog-v2",
        "vectors/adapter-protocol-v2-vectors.json": "adapter-protocol-v2-vectors",
    }
    for filename, key in json_files.items():
        with (base / filename).open("r", encoding="utf-8") as handle:
            documents[key] = json.load(handle)

    for key in ("openapi", "sse-event-v2", "adapter-protocol-v2",
                "command-catalog-v2", "adapter-protocol-v2-vectors"):
        if key not in documents:
            raise RuntimeError("契约文档缺失: " + key)
    return documents


def _enum_at(document, path):
    node = document
    for segment in path:
        if not isinstance(node, dict) or segment not in node:
            return None
        node = node[segment]
    if isinstance(node, dict) and isinstance(node.get("enum"), list):
        return [str(value) for value in node["enum"]]
    return None


def _compare(name, actual, expected, problems):
    if set(actual or []) != set(expected):
        missing = sorted(set(expected) - set(actual or []))
        extra = sorted(set(actual or []) - set(expected))
        problems.append("%s 枚举不一致: 缺少 %s 多出 %s" % (name, missing, extra))


def validate_json_schemas(documents):
    """结构性校验：四类文档必需内容存在。"""
    problems = []
    openapi = documents["openapi"]
    if not str(openapi.get("openapi", "")).startswith("3."):
        problems.append("openapi-v2.yaml 不是合法 OpenAPI 3 文档")
    if "/api/v2" not in json.dumps(openapi.get("servers", []), ensure_ascii=False):
        problems.append("OpenAPI servers 必须包含 /api/v2")

    adapter = documents["adapter-protocol-v2"]
    if adapter.get("x-protocol-version") != "2.0":
        problems.append("Adapter Schema x-protocol-version 必须为 2.0")
    if adapter.get("x-frame-max-bytes") != 1048576:
        problems.append("Adapter Schema 必须声明 1 MiB 帧上限")
    kinds = _enum_at(adapter, ["properties", "messageKind"])
    if set(kinds or []) != {"REQUEST", "RESPONSE", "EVENT"}:
        problems.append("Adapter Schema messageKind 枚举非法: %s" % kinds)

    sse = documents["sse-event-v2"]
    required = sse.get("required")
    if not isinstance(required, list):
        problems.append("SSE Schema 缺少 required 字段清单")
    else:
        for field in SSE_REQUIRED_FIELDS:
            if field not in required:
                problems.append("SSE Schema required 缺少字段 " + field)

    catalog = documents["command-catalog-v2"]
    commands = catalog.get("commands")
    if not isinstance(commands, list) or not commands:
        problems.append("命令目录缺少 commands 数组")
    return problems


def compare_enum_sets(documents):
    """OpenAPI ↔ 命令目录 ↔ 详细设计固定枚举的一致性比对。"""
    problems = []
    openapi = documents["openapi"]
    schemas = ["ExerciseGroupState", "AircraftLifecycle", "InstructionState",
               "ControlChannel"]
    expected = [EXERCISE_STATES, AIRCRAFT_LIFECYCLE, INSTRUCTION_STATES,
                CONTROL_CHANNELS]
    for schema_name, expected_values in zip(schemas, expected):
        _compare(schema_name,
                 _enum_at(openapi, ["components", "schemas", schema_name]),
                 expected_values, problems)

    _compare("ErrorResponse.code",
             _enum_at(openapi,
                      ["components", "schemas", "ErrorResponse", "properties", "code"]),
             ERROR_CODES, problems)

    instruction_types = set(
        _enum_at(openapi, ["components", "schemas", "InstructionType"]) or [])

    catalog = documents["command-catalog-v2"]
    alias_tokens = set()
    for command in catalog.get("commands", []):
        ctype = str(command.get("type"))
        creates = command.get("createsInstruction") is True
        if creates and ctype not in instruction_types:
            problems.append("命令 %s 创建指令但不在 InstructionType 枚举中" % ctype)
        if not creates and ctype in instruction_types:
            problems.append("命令 %s 不创建指令却出现在 InstructionType 枚举中" % ctype)
        channel = str(command.get("channel"))
        if channel not in CONTROL_CHANNELS and channel != "NONE":
            problems.append("命令 %s 使用未知控制通道 %s" % (ctype, channel))
        for alias in command.get("aliases") or []:
            if alias in instruction_types:
                problems.append("别名 %s 与规范命令类型撞名" % alias)
            if alias in alias_tokens:
                problems.append("别名 %s 在命令目录中重复出现" % alias)
            alias_tokens.add(alias)

    for instruction_type in sorted(instruction_types):
        declared = any(
            command.get("type") == instruction_type
            and command.get("createsInstruction") is True
            for command in catalog.get("commands", [])
        )
        if not declared:
            problems.append(
                "InstructionType 枚举值 %s 缺少 createsInstruction=true 条目"
                % instruction_type)
    return problems


def validate_vectors(documents):
    """与 Java AdapterProtocolFixture.validateEnvelope 同一规则的向量校验。"""
    problems = []
    adapter = documents["adapter-protocol-v2"]
    message_types = set(
        _enum_at(adapter, ["properties", "messageType"]) or [])
    vectors = documents["adapter-protocol-v2-vectors"].get("vectors")
    if not isinstance(vectors, list) or not vectors:
        return ["共享向量文件缺少 vectors 数组"]

    for vector in vectors:
        name = vector.get("name")
        envelope = vector.get("envelope") or {}
        envelope_problems = _validate_envelope(
            envelope, message_types, vector.get("correlatesToRequestId"))
        expect_valid = vector.get("expectValid") is True
        actual_valid = not envelope_problems
        if actual_valid != expect_valid:
            problems.append("向量 %s 校验结论与预期不符: %s"
                            % (name, envelope_problems))
    return problems


def _validate_envelope(envelope, message_types, correlates_to_request_id=None):
    problems = []
    if envelope.get("protocolVersion") != "2.0":
        problems.append("protocolVersion 必须为 2.0")
    kind = envelope.get("messageKind")
    if kind not in ("REQUEST", "RESPONSE", "EVENT"):
        problems.append("messageKind 非法: %s" % kind)
    if envelope.get("messageType") not in message_types:
        problems.append("messageType 不在枚举内: %s"
                        % envelope.get("messageType"))
    if not envelope.get("exerciseGroupId"):
        problems.append("exerciseGroupId 不能为空")
    if not envelope.get("engineInstanceId"):
        problems.append("engineInstanceId 不能为空")
    sequence = envelope.get("sequence")
    if not isinstance(sequence, int) or sequence < 1:
        problems.append("sequence 必须为不小于 1 的整数")
    if not envelope.get("systemTimeUtc"):
        problems.append("systemTimeUtc 不能为空")
    simulation_time = envelope.get("simulationTimeSeconds")
    if not isinstance(simulation_time, (int, float)) or simulation_time < 0:
        problems.append("simulationTimeSeconds 必须为不小于 0 的数值")
    if not isinstance(envelope.get("payload"), dict):
        problems.append("payload 必须是对象")
    if kind == "REQUEST":
        if envelope.get("correlationRequestId") is not None:
            problems.append("REQUEST 的 correlationRequestId 必须为空")
        if not envelope.get("requestId"):
            problems.append("REQUEST 必须携带 requestId")
    elif kind == "RESPONSE":
        if not envelope.get("correlationRequestId"):
            problems.append("RESPONSE 必须携带 correlationRequestId")
        if not envelope.get("requestId"):
            problems.append("RESPONSE 必须携带 requestId")
        if not envelope.get("idempotencyKey"):
            problems.append("RESPONSE 必须复制 REQUEST 的 idempotencyKey")
        if correlates_to_request_id is not None \
                and envelope.get("correlationRequestId") != correlates_to_request_id:
            problems.append(
                "RESPONSE 的 correlationRequestId 必须等于对应 REQUEST 的 requestId")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description="第二版契约校验")
    parser.add_argument("--contracts-dir", default=None,
                        help="契约目录，默认仓库 docs/contracts")
    parser.add_argument("--vectors", action="store_true",
                        help="只执行共享协议向量校验")
    args = parser.parse_args(argv)

    try:
        documents = load_documents(args.contracts_dir)
    except (OSError, RuntimeError, json.JSONDecodeError) as error:
        print("[FAIL] 加载契约失败: %s" % error)
        return 1

    sections = []
    if args.vectors:
        sections.append(("vectors", validate_vectors(documents)))
    else:
        sections = [
            ("schemas", validate_json_schemas(documents)),
            ("enum-sets", compare_enum_sets(documents)),
            ("vectors", validate_vectors(documents)),
        ]

    failed = False
    for name, problems in sections:
        if problems:
            failed = True
            for problem in problems:
                print("[FAIL][%s] %s" % (name, problem))
        else:
            print("[PASS][%s]" % name)
    if not failed:
        print("v2 contracts OK (%s)" % documents["base_dir"])
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
