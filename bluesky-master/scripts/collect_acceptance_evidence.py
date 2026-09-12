#!/usr/bin/env python3
"""汇总 AT-12 原始证据并生成 CapacityAcceptanceRunner 可校验的摘要。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping, Optional, Sequence


REQUEST_ID = re.compile(r"(?:requestId|X-Request-Id)[\"'=:\s]+([A-Za-z0-9._:-]+)")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Optional[Path]) -> Dict[str, Any]:
    if path is None:
        return {}
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON 根节点必须是对象: {path}")
    return value


def collect_metrics(paths: Iterable[Path]) -> Dict[str, Any]:
    """按参数顺序合并规范化指标；后文件覆盖前文件。"""
    merged: Dict[str, Any] = {}
    for path in paths:
        merged.update(read_json(path))
    return merged


def collect_logs(paths: Iterable[Path], sample_limit: int = 100) -> Dict[str, Any]:
    samples = []
    manifests = []
    for path in paths:
        manifests.append({"path": str(path), "sha256": sha256(path),
                          "sizeBytes": path.stat().st_size})
        if len(samples) >= sample_limit:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for request_id in REQUEST_ID.findall(text):
            if request_id not in samples:
                samples.append(request_id)
            if len(samples) >= sample_limit:
                break
    return {"files": manifests, "requestIdSamples": samples}


def verify_seed(expected_seed: int, evidence: Mapping[str, Any], source: str) -> None:
    actual = evidence.get("seed")
    if actual is None:
        raise ValueError(f"{source} 缺少固定随机种子")
    if int(actual) != expected_seed:
        raise ValueError(f"{source} 随机种子 {actual} 与期望 {expected_seed} 不一致")


def write_manifest(output: Path, seed: int, metrics: Mapping[str, Any],
                   raw_files: Iterable[Path], log_evidence: Mapping[str, Any]) -> Dict[str, Any]:
    files = []
    for path in raw_files:
        files.append({"path": str(path), "sha256": sha256(path),
                      "sizeBytes": path.stat().st_size})
    summary = dict(metrics)
    summary.update({
        "schemaVersion": "at12-capacity-summary/1",
        "seed": seed,
        "hardware": {
            "platform": platform.platform(),
            "processor": platform.processor(),
            "logicalCpuCount": os.cpu_count(),
        },
        "rawEvidence": files,
        "logs": dict(log_evidence),
    })
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="汇总 AT-12 容量验收证据")
    parser.add_argument("--seed", type=int, default=20260819)
    parser.add_argument("--metrics", type=Path, action="append", required=True,
                        help="规范化指标 JSON，可重复；后者覆盖前者")
    parser.add_argument("--raw", type=Path, action="append", default=[],
                        help="K6/SSE/Prometheus/故障调度等原始证据，可重复")
    parser.add_argument("--log", type=Path, action="append", default=[],
                        help="抽取 requestId 样本的日志，可重复")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    metrics = collect_metrics(args.metrics)
    verify_seed(args.seed, metrics, "metrics")
    for raw in args.raw:
        if raw.suffix.lower() == ".json":
            value = read_json(raw)
            if "seed" in value:
                verify_seed(args.seed, value, str(raw))
    logs = collect_logs(args.log)
    summary = write_manifest(args.output, args.seed, metrics,
                             [*args.metrics, *args.raw], logs)
    print(json.dumps({"output": str(args.output.resolve()),
                      "rawEvidenceCount": len(summary["rawEvidence"]),
                      "requestIdSampleCount": len(logs["requestIdSamples"])},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
