from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import socket
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from capture_helper.ipc import encode_event


def _send(connection: socket.socket, event: dict) -> None:
    connection.sendall(encode_event(event))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ipc-port", type=int, required=True)
    parser.add_argument("--proxy-port", type=int, required=True)
    parser.add_argument("--cert-dir", required=True)
    args = parser.parse_args()
    bootstrap = json.loads(sys.stdin.readline())
    scenario = os.environ.get("FLUENTMAI_MOCK_CAPTURE_SCENARIO", "success")

    connection = socket.create_connection(("127.0.0.1", args.ipc_port), timeout=5)
    try:
        token = str(bootstrap["token"])
        _send(connection, {"type": "auth", "token": "0" * 64 if scenario == "bad_auth" else token})
        if scenario == "bad_auth":
            return 0
        _send(connection, {"type": "certificate", "installed": False, "fingerprint": "fixture"})
        _send(connection, {"type": "ready", "helper_version": "fixture-helper"})
        if scenario == "error":
            _send(connection, {"type": "error", "category": "capture_timeout"})
            return 0
        if scenario == "crash":
            return 7
        if scenario == "hang":
            time.sleep(30)
            return 0

        _send(
            connection,
            {
                "type": "page",
                "page_kind": "home",
                "http_status": 200,
                "body": "<html>home fixture</html>",
                "bytes": 25,
            },
        )
        for difficulty in range(5):
            body = f"<html>difficulty {difficulty} fixture</html>"
            _send(
                connection,
                {
                    "type": "page",
                    "page_kind": "difficulty",
                    "difficulty": difficulty,
                    "http_status": 200,
                    "body": body,
                    "bytes": len(body),
                    "elapsed_ms": 1,
                },
            )
        _send(connection, {"type": "complete", "captured_pages": 5, "captured_bytes": 170})
        return 0
    finally:
        connection.close()


if __name__ == "__main__":
    raise SystemExit(main())
