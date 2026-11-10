from __future__ import annotations

import asyncio
import time
from datetime import datetime, timedelta, timezone

import httpx
import pytest

from notificme import (
    AskClosedError,
    AskPendingError,
    AsyncNotificMe,
    MemberToken,
    MemberUser,
    NotificMe,
    NotificMeApiError,
    NotificMeError,
    RevokedTokens,
    UntrustedText,
    VoiceAnswer,
    to_ask_result,
)

from .conftest import FakeServer

ACCEPTED = {"id": "evt-1", "status": "queued", "received_at": "2026-11-06T12:00:00Z"}

QUESTIONS = [
    {
        "id": "action",
        "type": "single",
        "text": "What should we do?",
        "options": ["Approve", "Deny"],
        "allow_other": True,
    },
    {"id": "ok", "type": "yesno", "text": "Ship it?"},
    {"id": "note", "type": "text", "text": "Notes", "optional": True},
    {"id": "memo", "type": "voice", "text": "Leave a memo", "optional": True},
]


def ask_view(status: str, reply: dict | None = None) -> dict:
    v = {
        "id": "ask-1",
        "status": status,
        "version": 1,
        "level": 1,
        "title": "Deploy?",
        "created_at": "2026-11-06T12:00:00Z",
    }
    if reply is not None:
        v["reply"] = reply
    return v


REPLY = {
    "question_set_id": "ask-1",
    "version": 1,
    "status": "answered",
    "answers": [
        {"question_id": "action", "question_text": "What should we do?", "value": "Approve"},
        {"question_id": "ok", "question_text": "Ship it?", "value": True},
        {"question_id": "note", "question_text": "Notes", "free_text": "Only after 10 pm"},
        {"question_id": "memo", "question_text": "Leave a memo", "audio_ref": "aud_1", "free_text": "hi"},
    ],
    "as_text": "Jimmy answered.",
    "answered_by": {
        "identity": "jimmy@idie.ai",
        "device_id": "dev-1",
        "level_used": 2,
        "auth_method": "biometric",
    },
    "answered_at": "2026-11-06T12:01:00Z",
    "signature": {"alg": "Ed25519", "key_id": "k1", "value": "c2ln"},
}


def test_requires_a_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("NOTIFICME_TOKEN", raising=False)
    with pytest.raises(NotificMeError) as e:
        NotificMe()
    assert e.value.code == "missing_token"


def test_reads_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NOTIFICME_TOKEN", "nm_svc_env")
    monkeypatch.setenv("NOTIFICME_URL", "https://cell.example.com/")
    c = NotificMe()
    assert c.base_url == "https://cell.example.com"
    assert c.keys_url == "https://cell.example.com/.well-known/notificme-keys.json"


def test_send_posts_wire_event(client: NotificMe, server: FakeServer) -> None:
    server.reply(202, ACCEPTED)
    r = client.send(
        "container.die",
        severity="high",
        summary="api-v2 died",
        dedup_key="vps-01/api-v2",
        ttl=1800,
        to="ops",
        payload={"exitCode": 137},
        card={"title": "Died", "blocks": [{"type": "metric", "label": "Crashes", "value": 3}]},
        occurred_at=datetime(2026, 11, 6, 12, 0, tzinfo=timezone.utc),
    )
    assert r.id == "evt-1" and r.status == "queued" and r.replayed is False
    req = server.requests[0]
    assert req.method == "POST" and req.url == "https://api.test/v0/events"
    assert req.headers["authorization"] == "Bearer nm_svc_test"
    assert req.headers["user-agent"].startswith("notificme-sdk-python/")
    body = server.json(0)
    assert body["version"] == "0" and body["source"] == "billing" and body["type"] == "container.die"
    assert body["id"]  # idempotency id generated
    assert body["to"] == ["ops"] and body["ttl"] == "1800s" and body["dedup_key"] == "vps-01/api-v2"
    assert body["payload"] == {"exitCode": 137}
    assert body["occurred_at"] == "2026-11-06T12:00:00.000Z"
    assert body["content"]["summary"] == "api-v2 died"
    assert body["content"]["card"]["blocks"][0]["label"] == "Crashes"


def test_send_defaults_and_validation(client: NotificMe, server: FakeServer) -> None:
    server.reply(202, ACCEPTED, {"Idempotent-Replayed": "true"})
    r = client.send("x.y", id="my-id", source="other")
    body = server.json(0)
    assert body["severity"] == "info" and body["id"] == "my-id" and body["source"] == "other"
    assert "content" not in body
    assert r.replayed is True
    with pytest.raises(TypeError):
        client.send(
            "x.y", card={"blocks": []}, summary=None, content={"card": {"blocks": [{"type": "text"}]}}
        )


def test_requires_source(server: FakeServer) -> None:
    c = NotificMe("t", http_client=httpx.Client(transport=httpx.MockTransport(server)))
    with pytest.raises(TypeError):
        c.send("x.y")


def test_retries_503_and_429_with_same_body(client: NotificMe, server: FakeServer) -> None:
    server.reply(503, {"error": {"code": "unavailable"}})
    server.reply(429, {"error": {"code": "rate_limited"}}, {"Retry-After": "0"})
    server.reply(202, ACCEPTED)
    client.send("x.y")
    assert len(server.requests) == 3
    assert server.requests[0].content == server.requests[2].content


def test_gives_up_and_exposes_api_error(client: NotificMe, server: FakeServer) -> None:
    for _ in range(4):
        server.reply(503, {"error": {"code": "unavailable", "message": "down"}, "request_id": "req-9"})
    with pytest.raises(NotificMeApiError) as e:
        client.send("x.y")
    assert e.value.status == 503 and e.value.code == "unavailable" and e.value.request_id == "req-9"
    assert len(server.requests) == 4


def test_does_not_retry_validation_errors(client: NotificMe, server: FakeServer) -> None:
    server.reply(422, {"error": {"code": "validation_failed", "details": {"type": "bad"}}})
    with pytest.raises(NotificMeApiError) as e:
        client.send("x.y")
    assert e.value.code == "validation_failed" and e.value.details == {"type": "bad"}
    assert len(server.requests) == 1


def test_network_error_after_retries(server: FakeServer) -> None:
    def boom(_: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    server.handler = boom
    c = NotificMe(
        "t",
        source="s",
        max_retries=1,
        retry_base_delay=0,
        http_client=httpx.Client(transport=httpx.MockTransport(server)),
    )
    with pytest.raises(NotificMeError) as e:
        c.send("x.y")
    assert e.value.code == "network_error" and len(server.requests) == 2


def test_live_card_update_reuses_fields(client: NotificMe, server: FakeServer) -> None:
    server.reply(202, ACCEPTED)
    server.reply(202, ACCEPTED)
    first = client.send(
        "deploy.progress",
        summary="Deploying api",
        severity="low",
        live=True,
        card={"blocks": [{"type": "progress", "value": 0.1}]},
    )
    assert first.card_id
    client.update(first.card_id, card={"blocks": [{"type": "progress", "value": 0.8}]})
    body = server.json(1)
    assert body["card_id"] == first.card_id and body["type"] == "deploy.progress"
    assert body["severity"] == "low" and body["content"]["summary"] == "Deploying api"


def test_update_unknown_card_needs_type(client: NotificMe) -> None:
    with pytest.raises(TypeError):
        client.update("card-x", summary="hi")


def test_ask_long_polls_until_reply(client: NotificMe, server: FakeServer) -> None:
    server.reply(201, ask_view("open"))
    server.reply(200, ask_view("open"))
    server.reply(200, ask_view("answered", REPLY))
    r = client.ask("Deploy?", QUESTIONS, to="ops", timeout="30m", on_timeout={"ok": False}, quorum=2)
    created = server.json(0)
    assert created["questions"][0]["allow_other"] is True
    assert created["timeout"] == "30m" and created["quorum"] == {"required": 2}
    assert created["on_timeout"] == {"ok": False}
    assert server.requests[0].headers["idempotency-key"]
    assert server.requests[1].url.params["wait"] == "60"
    assert r.status == "answered" and not r.timed_out
    assert r.answers["action"] == "Approve" and r.answers["ok"] is True
    assert r.answers["note"] == UntrustedText("Only after 10 pm")
    assert r.answers["memo"] == VoiceAnswer(text=UntrustedText("hi"), audio_ref="aud_1")
    assert r.answered_by is not None and r.answered_by.level_used == 2
    assert r.signature is not None and r.signature.key_id == "k1"
    assert r.details["note"].free_text == UntrustedText("Only after 10 pm")


def test_ask_require_signature(client: NotificMe, server: FakeServer) -> None:
    e_signature = {
        "name": "Ana Souza",
        "initials": "AS",
        "method": "drawn",
        "signature_png": "iVBORw0KGgo=",
        "signature_sha256": "sha256:" + "a" * 64,
        "initials_png": "iVBORw0KGgo=",
        "initials_sha256": "sha256:" + "b" * 64,
        "consent_version": "esign-2026-11-09",
        "adopted_at": "2026-11-02T09:12:40Z",
    }
    server.reply(201, ask_view("open"))
    server.reply(200, ask_view("answered", dict(REPLY, e_signature=e_signature)))
    r = client.ask("Renew the contract?", QUESTIONS, to="ana", require_signature=True)
    assert server.json(0)["require_signature"] is True
    assert r.e_signature is not None
    assert r.e_signature.name == "Ana Souza" and r.e_signature.method == "drawn"
    assert r.e_signature.style is None
    assert r.e_signature.signature_sha256 == "sha256:" + "a" * 64
    assert r.e_signature.adopted_at == "2026-11-02T09:12:40Z"


def test_ask_require_face(client: NotificMe, server: FakeServer) -> None:
    face = {
        "result": "verified",
        "verification_id": "01a1c251-15ad-7d0e-8aac-3684e665169c",
        "model": "yunet-2023mar+sface-2021dec",
        "verified_at": "2026-11-10T14:03:11Z",
    }
    server.reply(201, ask_view("open"))
    server.reply(200, ask_view("answered", dict(REPLY, face_verification=face)))
    r = client.ask("Sign the contract?", QUESTIONS, to="ana", require_face=True)
    assert server.json(0)["require_face"] is True
    assert r.face_verification is not None
    assert r.face_verification.result == "verified" and r.face_verification.reason is None
    assert r.face_verification.model == "yunet-2023mar+sface-2021dec"
    assert r.face_verification.verified_at == "2026-11-10T14:03:11Z"


def test_ask_face_fallback(client: NotificMe, server: FakeServer) -> None:
    face = {"result": "fallback", "reason": "not_enrolled", "verified_at": "2026-11-10T14:03:11Z"}
    server.reply(201, ask_view("open"))
    server.reply(200, ask_view("answered", dict(REPLY, face_verification=face)))
    r = client.ask("Sign the contract?", QUESTIONS, require_face=True)
    assert r.face_verification is not None
    assert r.face_verification.result == "fallback" and r.face_verification.reason == "not_enrolled"
    assert r.face_verification.verification_id is None


def test_ask_without_signature(client: NotificMe, server: FakeServer) -> None:
    server.reply(201, ask_view("open"))
    server.reply(200, ask_view("answered", REPLY))
    r = client.ask("Deploy?", QUESTIONS)
    assert "require_signature" not in server.json(0)
    assert r.e_signature is None
    assert "require_face" not in server.json(0)
    assert r.face_verification is None


def test_ask_single_other_is_untrusted(client: NotificMe, server: FakeServer) -> None:
    reply = dict(REPLY, answers=[{"question_id": "action", "question_text": "?", "free_text": "Wait"}])
    server.reply(201, ask_view("answered", reply))
    server.reply(200, ask_view("answered", reply))
    r = client.ask("Deploy?", QUESTIONS)
    assert r.answers["action"] == UntrustedText("Wait") and r.answers["ok"] is None


def test_ask_closed(client: NotificMe, server: FakeServer) -> None:
    server.reply(201, ask_view("open"))
    server.reply(200, ask_view("expired"))
    with pytest.raises(AskClosedError) as e:
        client.ask("Deploy?", QUESTIONS)
    assert e.value.status == "expired"


def test_ask_pending_and_resume(client: NotificMe, server: FakeServer) -> None:
    def still_open(req: httpx.Request) -> httpx.Response:
        if req.method == "GET":
            time.sleep(0.02)  # the server holds the long-poll
        return httpx.Response(201 if req.method == "POST" else 200, json=ask_view("open"))

    server.handler = still_open
    with pytest.raises(AskPendingError) as e:
        client.ask("Deploy?", QUESTIONS, max_wait=0.05)
    assert server.requests[1].url.params["wait"] == "1"
    assert all(r.method == "GET" for r in server.requests[1:])
    server.handler = None
    server.reply(200, ask_view("timeout", dict(REPLY, status="timeout")))
    r = client.wait_for_reply(e.value.ask_id, questions=QUESTIONS)
    assert r.timed_out and r.status == "timeout"


def test_create_ask_handle(client: NotificMe, server: FakeServer) -> None:
    server.reply(201, ask_view("open"))
    h = client.create_ask("Deploy?", QUESTIONS, idempotency_key="idem-1")
    assert h.id == "ask-1" and h.status == "open"
    assert server.requests[0].headers["idempotency-key"] == "idem-1"
    server.reply(200, ask_view("answered", REPLY))
    assert h.wait().answers["ok"] is True


def test_async_client(server: FakeServer) -> None:
    async def run() -> None:
        async with AsyncNotificMe(
            "t",
            base_url="https://api.test",
            source="agent",
            retry_base_delay=0,
            http_client=httpx.AsyncClient(transport=httpx.MockTransport(server)),
        ) as c:
            server.reply(503, {})
            server.reply(202, ACCEPTED)
            await c.send("x.y", summary="hi")
            server.reply(201, ask_view("open"))
            server.reply(200, ask_view("answered", REPLY))
            r = await c.ask("Deploy?", QUESTIONS)
            assert r.answers["action"] == "Approve"
            server.reply(201, ask_view("open"))
            h = await c.create_ask("Deploy?", QUESTIONS)
            server.reply(200, ask_view("answered", REPLY))
            assert (await h.wait()).answers["ok"] is True

    asyncio.run(run())
    assert len(server.requests) == 6


ISSUED = {
    "token": "nm_dlg_abc",
    "token_type": "Bearer",
    "expires_at": "2026-11-06T13:00:00Z",
    "workspace_id": "ws-1",
    "user": {
        "id": "user-1",
        "email": "ana@acme.com",
        "handle": "ana",
        "display_name": "Ana Lima",
        "locale": "pt-BR",
        "avatar_url": "",
        "created_at": "2026-11-06T12:00:00Z",
    },
    "membership_created": True,
}


def test_issue_member_token(client: NotificMe, server: FakeServer) -> None:
    server.reply(201, ISSUED)
    t = client.members.issue_token(
        email="ana@acme.com", external_id="u-123", display_name="Ana Lima", ttl="30m"
    )
    req = server.requests[0]
    assert req.method == "POST" and req.url == "https://api.test/v0/member-tokens"
    assert req.headers["authorization"] == "Bearer nm_svc_test"
    assert server.json(0) == {
        "email": "ana@acme.com",
        "external_id": "u-123",
        "display_name": "Ana Lima",
        "ttl": "30m",
    }
    assert t == MemberToken(
        token="nm_dlg_abc",
        token_type="Bearer",
        expires_at="2026-11-06T13:00:00Z",
        workspace_id="ws-1",
        user=MemberUser(
            id="user-1",
            email="ana@acme.com",
            handle="ana",
            display_name="Ana Lima",
            locale="pt-BR",
            created_at="2026-11-06T12:00:00Z",
        ),
        membership_created=True,
    )


def test_issue_member_token_defaults_and_ttl(client: NotificMe, server: FakeServer) -> None:
    server.reply(201, dict(ISSUED, membership_created=False))
    server.reply(201, ISSUED)
    t = client.members.issue_token("ana@acme.com")
    client.members.issue_token("ana@acme.com", ttl=timedelta(hours=2))
    assert server.json(0) == {"email": "ana@acme.com"}
    assert server.json(1) == {"email": "ana@acme.com", "ttl": "7200s"}
    assert t.membership_created is False
    with pytest.raises(TypeError):
        client.members.issue_token("")


def test_issue_member_token_blocked(client: NotificMe, server: FakeServer) -> None:
    server.reply(403, {"error": {"code": "workspace_blocked", "message": "blocked"}})
    with pytest.raises(NotificMeApiError) as e:
        client.members.issue_token("ana@acme.com")
    assert e.value.status == 403 and e.value.code == "workspace_blocked"
    assert len(server.requests) == 1


def test_revoke_member_tokens(client: NotificMe, server: FakeServer) -> None:
    server.reply(200, {"revoked": 2})
    r = client.members.revoke_tokens(email="ana@acme.com")
    assert r == RevokedTokens(revoked=2)
    req = server.requests[0]
    assert req.method == "POST" and req.url == "https://api.test/v0/member-tokens/revoke"
    assert server.json(0) == {"email": "ana@acme.com"}
    with pytest.raises(TypeError):
        client.members.revoke_tokens("")


def test_async_members(server: FakeServer) -> None:
    async def run() -> None:
        async with AsyncNotificMe(
            "t",
            base_url="https://api.test",
            retry_base_delay=0,
            http_client=httpx.AsyncClient(transport=httpx.MockTransport(server)),
        ) as c:
            server.reply(503, {})
            server.reply(201, ISSUED)
            t = await c.members.issue_token("ana@acme.com", ttl="1h")
            assert t.token == "nm_dlg_abc" and t.user.display_name == "Ana Lima"
            server.reply(200, {"revoked": 1})
            assert (await c.members.revoke_tokens("ana@acme.com")).revoked == 1

    asyncio.run(run())
    assert len(server.requests) == 3
    assert server.json(1) == {"email": "ana@acme.com", "ttl": "1h"}


def test_delegated_auth_method() -> None:
    reply = dict(
        REPLY,
        answered_by={
            "identity": "ana@acme.com",
            "device_id": "dlg-1",
            "level_used": 1,
            "auth_method": "delegated",
        },
    )
    r = to_ask_result(reply)
    assert r.answered_by is not None and r.answered_by.auth_method == "delegated"


def test_decision_and_escalation_wire():
    from notificme.wire import ask_to_wire

    body = ask_to_wire(
        title="Contract 4521",
        questions=[{"id": "ok", "type": "yesno", "text": "Approve?"}],
        to="#finance",
        decision={
            "by": "#board>=director",
            "rule": "quorum",
            "required": 2,
            "requested_by": "@ana",
            "veto": None,
        },
        escalate=[{"after": "30m", "to": "#ceo"}, "@cfo"],
    )
    assert body["to"] == ["#finance"]
    assert body["decision"] == {
        "by": ["#board>=director"],
        "rule": "quorum",
        "required": 2,
        "requested_by": "@ana",
    }
    assert body["escalate"] == [{"after": "30m", "to": ["#ceo"]}, "@cfo"]


def test_reply_closed_by_rule():
    reply = {
        "question_set_id": "q1",
        "version": 1,
        "status": "rejected",
        "answers": [{"question_id": "ok", "question_text": "Approve?", "value": False}],
        "answered_by": {
            "identity": "bruno@acme.com",
            "device_id": "d",
            "level_used": 1,
            "group": "#board",
            "authority_level": "director",
        },
        "decision": {
            "rule": "quorum",
            "required": 2,
            "eligible": 3,
            "approvals": 0,
            "rejections": 2,
            "outcome": "rejected",
        },
        "votes": [{"identity": "ana@acme.com", "approve": False, "answered_at": "2026-11-09T14:30:00Z"}],
        "signature": {"alg": "Ed25519", "value": "x"},
    }
    r = to_ask_result(reply, [{"id": "ok", "type": "yesno", "text": "Approve?"}])
    assert r.status == "rejected" and r.decision["outcome"] == "rejected" and len(r.votes) == 1
    assert r.answered_by.group == "#board" and r.answered_by.authority_level == "director"
