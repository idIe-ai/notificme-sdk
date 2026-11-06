from __future__ import annotations

import asyncio
import base64
import json

import httpx
import pytest

from notificme import (
    KeysUrl,
    NotificMe,
    UntrustedText,
    WebhookVerificationError,
    areceive_webhook,
    parse_signature_header,
    receive_webhook,
    verify_webhook,
)

from .conftest import VECTOR, FakeServer

NOW = VECTOR["timestamp"] + 10
BODY = VECTOR["body"]
HEADER = VECTOR["header"]
PUB = VECTOR["public_key"]


def reason(fn) -> str:  # type: ignore[no-untyped-def]
    with pytest.raises(WebhookVerificationError) as e:
        fn()
    return e.value.reason


def test_accepts_every_key_form() -> None:
    v = verify_webhook(BODY, HEADER, PUB, now=NOW)
    assert v.timestamp == VECTOR["timestamp"] and v.key_id == VECTOR["key_id"]
    raw = base64.urlsafe_b64decode(PUB + "=")
    verify_webhook(BODY, HEADER, raw, now=NOW)
    verify_webhook(BODY, HEADER, VECTOR["jwks"]["keys"][0], now=NOW)
    verify_webhook(BODY, HEADER, VECTOR["jwks"], now=NOW)
    verify_webhook(BODY.encode("utf-8"), HEADER, PUB, now=NOW)
    verify_webhook(bytearray(BODY.encode("utf-8")), HEADER, PUB, now=NOW)


def test_rejects_tampered_and_reserialized_body() -> None:
    assert (
        reason(lambda: verify_webhook(BODY.replace("Approve", "Deny"), HEADER, PUB, now=NOW))
        == "invalid_signature"
    )
    pretty = json.dumps(json.loads(BODY), indent=2)
    assert reason(lambda: verify_webhook(pretty, HEADER, PUB, now=NOW)) == "invalid_signature"


def test_rejects_forged_signature_and_other_key() -> None:
    forged = HEADER.split("sig=")[0] + "sig=" + base64.urlsafe_b64encode(b"\x01" * 64).decode().rstrip("=")
    assert reason(lambda: verify_webhook(BODY, forged, PUB, now=NOW)) == "invalid_signature"
    other = base64.urlsafe_b64encode(b"\x07" * 32).decode().rstrip("=")
    assert reason(lambda: verify_webhook(BODY, HEADER, other, now=NOW)) in (
        "invalid_signature",
        "invalid_key",
    )


def test_header_and_timestamp_checks() -> None:
    assert reason(lambda: verify_webhook(BODY, None, PUB, now=NOW)) == "missing_header"
    assert reason(lambda: verify_webhook(BODY, "garbage", PUB, now=NOW)) == "malformed_header"
    assert reason(lambda: verify_webhook(BODY, HEADER, PUB, now=NOW + 301)) == "timestamp_out_of_tolerance"
    verify_webhook(BODY, HEADER, PUB, now=NOW + 10_000, tolerance_seconds=0)


def test_unknown_kid_in_jwks() -> None:
    jwks = {"keys": [dict(VECTOR["jwks"]["keys"][0], kid="other")]}
    assert reason(lambda: verify_webhook(BODY, HEADER, jwks, now=NOW)) == "unknown_key"


def test_parse_signature_header() -> None:
    assert parse_signature_header("t=1,kid=k,sig=s,x=y") == {"t": "1", "kid": "k", "sig": "s"}
    assert parse_signature_header("t=1") is None


def headers() -> dict:
    return {
        "notificme-signature": HEADER,
        "Notificme-Webhook-Id": "wh-1",
        "NOTIFICME-TOPIC": "reply",
        "notificme-attempt": "2",
    }


def test_receive_webhook_parses_reply() -> None:
    questions = [
        {"id": "action", "type": "single", "text": "What should we do?", "options": ["Approve", "Deny"]},
        {"id": "note", "type": "text", "text": "Notes"},
    ]
    hook = receive_webhook(BODY, headers(), PUB, questions=questions, now=NOW)
    assert hook.id == "wh-1" and hook.topic == "reply" and hook.attempt == 2
    assert hook.reply is not None and hook.reply.answers["action"] == "Approve"
    assert hook.reply.answers["note"] == UntrustedText("Only after 10 pm ✓")
    assert hook.payload["question_set_id"] == hook.reply.id


def test_keys_url_is_fetched_and_cached(server: FakeServer) -> None:
    server.handler = lambda req: httpx.Response(200, json=VECTOR["jwks"])
    http = httpx.Client(transport=httpx.MockTransport(server))
    key = KeysUrl("https://api.test/.well-known/notificme-keys.json", http_client=http)
    verify_webhook(BODY, HEADER, key, now=NOW)
    verify_webhook(BODY, HEADER, key, now=NOW)
    assert len(server.requests) == 1


def test_client_and_async_receive(server: FakeServer) -> None:
    server.handler = lambda req: httpx.Response(200, json=VECTOR["jwks"])
    c = NotificMe(
        "t", base_url="https://api.test", http_client=httpx.Client(transport=httpx.MockTransport(server))
    )
    assert c.receive_webhook(BODY, headers(), now=NOW).reply is not None
    assert str(server.requests[0].url) == "https://api.test/.well-known/notificme-keys.json"

    async def run() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as ac:
            key = KeysUrl("https://other.test/keys.json", async_http_client=ac)
            hook = await areceive_webhook(BODY.encode(), headers(), key, now=NOW)
            assert hook.reply is not None

    asyncio.run(run())
