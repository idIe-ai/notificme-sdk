from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable, Dict, List

import httpx
import pytest

import notificme
import notificme.client
from notificme import clear_webhook_key_cache

VECTOR_PATH = Path(__file__).parents[2] / "protocol" / "test-vectors" / "webhook-signature.json"
VECTOR: Dict[str, Any] = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))


class FakeServer:
    """Records requests and answers with queued responses (or a handler)."""

    def __init__(self) -> None:
        self.requests: List[httpx.Request] = []
        self.queue: List[httpx.Response] = []
        self.handler: Callable[[httpx.Request], httpx.Response] | None = None

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.handler is not None:
            return self.handler(request)
        if not self.queue:
            raise AssertionError(f"unexpected request {request.method} {request.url}")
        return self.queue.pop(0)

    def reply(self, status: int, body: Any = None, headers: Dict[str, str] | None = None) -> None:
        self.queue.append(httpx.Response(status, json=body, headers=headers))

    def json(self, i: int) -> Any:
        return json.loads(self.requests[i].content)


@pytest.fixture
def server() -> FakeServer:
    return FakeServer()


@pytest.fixture
def client(server: FakeServer) -> notificme.NotificMe:
    return notificme.NotificMe(
        "nm_svc_test",
        base_url="https://api.test",
        source="billing",
        retry_base_delay=0,
        http_client=httpx.Client(transport=httpx.MockTransport(server)),
    )


@pytest.fixture(autouse=True)
def _fast(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(notificme.client, "MIN_POLL_INTERVAL", 0.0)
    clear_webhook_key_cache()
