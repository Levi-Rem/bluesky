"""Executable ZeroMQ adapter process embedding one detached BlueSky engine."""

import argparse
import json
import logging
import os
import signal
import threading
import time
import uuid
from pathlib import Path
from typing import Optional, Sequence

import zmq

from .engine import BlueSkyEngine
from .protocol import AdapterProtocol


LOGGER = logging.getLogger(__name__)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="BlueSky training platform adapter")
    parser.add_argument(
        "--control-endpoint",
        default=os.environ.get("BS_ADAPTER_CONTROL_ENDPOINT", "tcp://127.0.0.1:5555"),
    )
    parser.add_argument(
        "--state-endpoint",
        default=os.environ.get("BS_ADAPTER_STATE_ENDPOINT", "tcp://127.0.0.1:5556"),
    )
    parser.add_argument("--workdir", default=str(Path.cwd()))
    return parser


def run(args: argparse.Namespace) -> int:
    engine = BlueSkyEngine(args.workdir)
    engine.initialize()
    protocol = AdapterProtocol(engine)

    context = zmq.Context()
    control = context.socket(zmq.REP)
    control.linger = 0
    control.bind(args.control_endpoint)
    state = context.socket(zmq.PUB)
    state.linger = 0
    state.bind(args.state_endpoint)

    stopped = threading.Event()

    def stop(_signum=None, _frame=None):
        stopped.set()

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    if hasattr(signal, "SIGBREAK"):
        signal.signal(signal.SIGBREAK, stop)

    poller = zmq.Poller()
    poller.register(control, zmq.POLLIN)
    sequence = 0
    instance_id = str(uuid.uuid4())
    next_state_publish = time.monotonic()
    try:
        while not stopped.is_set():
            events = dict(poller.poll(10))
            if control in events:
                try:
                    request = json.loads(control.recv().decode("utf-8"))
                    response = protocol.handle(request)
                except (UnicodeDecodeError, json.JSONDecodeError) as exception:
                    response = {
                        "protocolVersion": "1.0",
                        "requestId": "",
                        "success": False,
                        "code": "INVALID_JSON",
                        "message": str(exception),
                        "payload": {},
                    }
                control.send(
                    json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode(
                        "utf-8"
                    )
                )
            try:
                engine.update()
            except Exception:
                LOGGER.exception("BlueSky engine update failed; adapter loop will continue")
            now = time.monotonic()
            if now >= next_state_publish:
                sequence += 1
                snapshot = engine.snapshot()
                snapshot.update(
                    {
                        "protocolVersion": "1.0",
                        "instanceId": instance_id,
                        "sequence": sequence,
                        "exerciseGroupId": "GROUP-DEFAULT",
                    }
                )
                state.send_multipart(
                    [
                        b"state.GROUP-DEFAULT",
                        json.dumps(
                            snapshot, ensure_ascii=False, separators=(",", ":")
                        ).encode("utf-8"),
                    ]
                )
                next_state_publish = now + 1.0
    finally:
        poller.unregister(control)
        control.close()
        state.close()
        context.term()
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
