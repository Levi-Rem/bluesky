#!/usr/bin/env python3
"""AT-12 可靠 SSE 多连接/断网重连伴随负载。"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import random
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence


def identity_headers(secret: str, target: Mapping[str, Any], last_event_id: str = "") -> Dict[str, str]:
    headers = {
        "Accept": "text/event-stream",
        "X-Gateway-Secret": secret,
        "X-Trusted-Caller-Type": "TERMINAL",
        "X-Trusted-Caller-Id": str(target["terminalId"]),
        "X-Trusted-Terminal-Id": str(target["terminalId"]),
        "X-Trusted-Fingerprint-Digest": str(target["fingerprintDigest"]),
    }
    if last_event_id:
        headers["Last-Event-ID"] = last_event_id
    return headers


class Counters:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.events = 0
        self.connections = 0
        self.reconnects = 0
        self.errors = 0
        self.duplicates = 0
        self.last_ids: Dict[str, str] = {}
        self.seen_ids: Dict[str, set[str]] = {}

    def update(self, key: str, event_id: str) -> None:
        with self.lock:
            self.events += 1
            if event_id:
                seen = self.seen_ids.setdefault(key, set())
                if event_id in seen:
                    self.duplicates += 1
                seen.add(event_id)
                self.last_ids[key] = event_id

    def snapshot(self) -> Dict[str, Any]:
        with self.lock:
            return {
                "events": self.events,
                "connections": self.connections,
                "reconnects": self.reconnects,
                "errors": self.errors,
                "duplicates": self.duplicates,
                "lastEventIds": dict(self.last_ids),
            }


def stream_client(base_url: str, secret: str, target: Mapping[str, Any], slot: int,
                  deadline: float, disconnect_every: float, seed: int,
                  counters: Counters) -> None:
    rng = random.Random(seed + slot)
    key = f"{target['groupId']}:{target['terminalId']}:{slot}"
    last_event_id = ""
    first = True
    while time.monotonic() < deadline:
        query = urllib.parse.urlencode({"exerciseGroupId": target["groupId"],
                                        "terminalId": target["terminalId"]})
        req = urllib.request.Request(
            f"{base_url.rstrip('/')}/api/v2/events?{query}",
            headers=identity_headers(secret, target, last_event_id))
        connected_at = time.monotonic()
        forced_after = disconnect_every * rng.uniform(0.75, 1.25) if disconnect_every else None
        try:
            with urllib.request.urlopen(req, timeout=65) as response:
                with counters.lock:
                    counters.connections += 1
                    if not first:
                        counters.reconnects += 1
                first = False
                current_id = ""
                while time.monotonic() < deadline:
                    raw = response.readline()
                    if not raw:
                        break
                    line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                    if line.startswith("id:"):
                        current_id = line[3:].strip()
                    elif line == "":
                        counters.update(key, current_id)
                        if current_id:
                            last_event_id = current_id
                        current_id = ""
                    if forced_after and time.monotonic() - connected_at >= forced_after:
                        break
        except (urllib.error.URLError, TimeoutError, OSError):
            with counters.lock:
                counters.errors += 1
        time.sleep(min(1.0, max(0.0, deadline - time.monotonic())))


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="AT-12 64 路 SSE soak")
    parser.add_argument("--config", type=Path, required=True,
                        help="JSON: baseUrl + 8 targets，每 target 至少 8 terminals")
    parser.add_argument("--duration-seconds", type=int, default=8 * 60 * 60)
    parser.add_argument("--clients", type=int, default=64)
    parser.add_argument("--disconnect-every-seconds", type=float, default=0,
                        help="大于 0 时周期性模拟断网并使用 Last-Event-ID 重连")
    parser.add_argument("--seed", type=int, default=20260819)
    parser.add_argument("--output", type=Path,
                        default=Path("loadtest/artifacts/sse-summary.json"))
    args = parser.parse_args(argv)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    secret = os.environ.get("BS_ACCEPTANCE_GATEWAY_SECRET", "")
    if not secret:
        raise SystemExit("BS_ACCEPTANCE_GATEWAY_SECRET 未设置")
    terminals = []
    for group in config.get("targets", []):
        group_terminals = group.get("terminals") or [group]
        for terminal in group_terminals:
            terminals.append({"groupId": group["groupId"], **terminal})
    if len(terminals) < args.clients:
        raise SystemExit(f"需要 {args.clients} 个终端身份，配置只有 {len(terminals)} 个")

    counters = Counters()
    deadline = time.monotonic() + args.duration_seconds
    started_at = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.clients) as pool:
        futures = [pool.submit(stream_client, config["baseUrl"], secret, terminals[index],
                               index, deadline, args.disconnect_every_seconds,
                               args.seed, counters) for index in range(args.clients)]
        for future in futures:
            future.result()
    summary = {
        "schemaVersion": "at12-sse-soak/1",
        "seed": args.seed,
        "requestedClients": args.clients,
        "durationSeconds": time.time() - started_at,
        **counters.snapshot(),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(args.output.resolve())
    return 1 if summary["errors"] or summary["duplicates"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
