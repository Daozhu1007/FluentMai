from __future__ import annotations

import asyncio
import json
import socket
import struct


MAX_FRAME_BYTES = 32 * 1024 * 1024


class AsyncIpcClient:
    def __init__(self, port: int, token: str):
        self.port = int(port)
        self.token = token
        self.reader: asyncio.StreamReader | None = None
        self.writer: asyncio.StreamWriter | None = None
        self._lock = asyncio.Lock()

    async def connect(self) -> None:
        self.reader, self.writer = await asyncio.open_connection("127.0.0.1", self.port)
        await self.send({"type": "auth", "token": self.token})

    async def send(self, event: dict) -> None:
        if self.writer is None:
            raise RuntimeError("IPC connection is not open.")
        frame = encode_event(event)
        async with self._lock:
            self.writer.write(frame)
            await self.writer.drain()

    async def close(self) -> None:
        if self.writer is None:
            return
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except (ConnectionError, OSError):
            pass
        self.writer = None
        self.reader = None


def encode_event(event: dict) -> bytes:
    payload = json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload) > MAX_FRAME_BYTES:
        raise ValueError("IPC event is too large.")
    return struct.pack("!I", len(payload)) + payload


def receive_event(connection: socket.socket, timeout: float | None = None) -> dict:
    if timeout is not None:
        connection.settimeout(timeout)
    header = _receive_exact(connection, 4)
    length = struct.unpack("!I", header)[0]
    if length <= 0 or length > MAX_FRAME_BYTES:
        raise ValueError("Invalid IPC frame length.")
    payload = _receive_exact(connection, length)
    value = json.loads(payload.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("IPC event must be a JSON object.")
    return value


def _receive_exact(connection: socket.socket, size: int) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        chunk = connection.recv(remaining)
        if not chunk:
            raise ConnectionError("IPC connection closed before the frame completed.")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)
