#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P03/P04：REFERENCE_SNAPSHOT_LOAD checksum 契约桩。

以 Protocol 2.0 AdapterRuntime 起一个最小 Adapter：
- REFERENCE_SNAPSHOT_LOAD → 校验 manifest 并在 ACK 中返回 manifest checksum；
- HELLO → 简单握手。
Java 侧契约测试（ReferenceSnapshotLoadContractTest）通过 DEALER 调用本桩，
验证两侧对同一 manifest 得出相同 checksum。
"""
import argparse
import hashlib
import json
import os
import sys
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from bluesky.plugins.training_adapter.runner_v2 import AdapterRuntime  # noqa: E402

SCHEMA_PATH = os.path.join(
    os.path.dirname(__file__), "..", "docs", "contracts",
    "adapter-protocol-v2.schema.json")


def manifest_checksum(payload):
    manifest_json = payload.get("manifestJson")
    if not manifest_json:
        return {"accepted": False, "code": "MANIFEST_MISSING",
                "message": "缺少 manifestJson"}
    checksum = hashlib.sha256(manifest_json.encode("utf-8")).hexdigest()
    return {"accepted": True, "code": "OK",
            "manifestChecksum": checksum,
            "resourceCount": len(payload.get("resources", {}))}


def main(argv=None):
    parser = argparse.ArgumentParser(description="reference snapshot adapter stub")
    parser.add_argument("--control-endpoint", required=True)
    parser.add_argument("--state-endpoint", required=True)
    parser.add_argument("--engine-instance-id",
                        default="engine-" + uuid.uuid4().hex[:12])
    args = parser.parse_args(argv)

    runtime = AdapterRuntime(
        {
            "HELLO": lambda payload: {"connected": True},
            "REFERENCE_SNAPSHOT_LOAD": manifest_checksum,
        },
        control_endpoint=args.control_endpoint,
        state_endpoint=args.state_endpoint,
        exercise_group_id="group-contract",
        engine_instance_id=args.engine_instance_id,
        schema_path=SCHEMA_PATH)
    print(json.dumps({"control": args.control_endpoint,
                      "state": args.state_endpoint,
                      "engineInstanceId": args.engine_instance_id}),
          flush=True)
    runtime.run_control_loop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
