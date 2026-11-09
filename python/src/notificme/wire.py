"""Conversion between SDK arguments/results and the protocol wire format.

The protocol schemas use ``additionalProperties: false``, so fields are mapped
one by one instead of forwarding arbitrary keys.
"""

from __future__ import annotations

import math
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Union

from .types import (
    AnswerDetail,
    AnsweredBy,
    AskInfo,
    AskResult,
    ESignature,
    MemberToken,
    MemberUser,
    ReplySignature,
    SendResult,
    VoiceAnswer,
)
from .untrusted import UntrustedText

Duration = Union[str, int, float, timedelta]

QUESTION_TYPES = frozenset({"yesno", "single", "multi", "text", "voice", "number", "date"})


def new_id() -> str:
    return str(uuid.uuid4())


def compact(d: Dict[str, Any]) -> Dict[str, Any]:
    return {k: v for k, v in d.items() if v is not None}


def _list(v: Union[str, Iterable[str], None]) -> Optional[List[str]]:
    if v is None:
        return None
    if isinstance(v, str):
        return [v]
    return list(v)


def duration(v: Optional[Duration]) -> Optional[str]:
    """``"30m"`` stays as is; a number (seconds) or a ``timedelta`` becomes ``"<n>s"``."""
    if v is None:
        return None
    if isinstance(v, timedelta):
        v = v.total_seconds()
    if isinstance(v, bool):
        raise TypeError(f"Invalid duration: {v!r}")
    if isinstance(v, (int, float)):
        if not math.isfinite(v) or v < 0:
            raise ValueError(f"Invalid duration: {v!r}")
        return f"{round(v)}s"
    return v


def iso(v: Union[datetime, str, None], now: datetime) -> str:
    if v is None:
        v = now
    if isinstance(v, str):
        return v
    # Naive datetimes are taken as local time, like datetime.astimezone().
    s = v.astimezone(timezone.utc).isoformat(timespec="milliseconds")
    return s.replace("+00:00", "Z")


def event_to_wire(
    *,
    id: str,
    source: str,
    type: str,
    now: datetime,
    severity: Optional[str] = None,
    occurred_at: Union[datetime, str, None] = None,
    to: Union[str, Iterable[str], None] = None,
    payload: Optional[Mapping[str, Any]] = None,
    dedup_key: Optional[str] = None,
    ttl: Optional[Duration] = None,
    expires_when: Optional[str] = None,
    card_id: Optional[str] = None,
    retention: Optional[str] = None,
    summary: Optional[str] = None,
    card: Optional[Mapping[str, Any]] = None,
    content: Optional[Mapping[str, Any]] = None,
) -> Dict[str, Any]:
    """Event arguments → ``event.schema.json``."""
    wire_content: Optional[Dict[str, Any]] = None
    if content or summary is not None or card:
        c = content or {}
        s = summary if summary is not None else c.get("summary")
        if s is None:
            raise TypeError("An event with a card needs a `summary`")
        wire_content = compact(
            {"summary": s, "card": dict(card or c.get("card") or {}) or None, "rich": c.get("rich")}
        )
    return compact(
        {
            "version": "0",
            "id": id,
            "source": source,
            "type": type,
            "severity": severity or "info",
            "occurred_at": iso(occurred_at, now),
            "to": _list(to),
            "payload": dict(payload) if payload is not None else None,
            "dedup_key": dedup_key,
            "ttl": duration(ttl),
            "expires_when": expires_when,
            "card_id": card_id,
            "retention": retention,
            "content": wire_content,
        }
    )


def send_result_from_wire(r: Mapping[str, Any], card_id: Optional[str], replayed: bool) -> SendResult:
    return SendResult(
        id=r["id"],
        status=r["status"],
        received_at=r.get("received_at", ""),
        replayed=replayed,
        duplicate_of=r.get("duplicate_of"),
        sender_id=r.get("sender_id"),
        card_id=card_id,
    )


def question_to_wire(q: Mapping[str, Any]) -> Dict[str, Any]:
    qtype = q.get("type")
    if qtype not in QUESTION_TYPES:
        raise TypeError(f"Unknown question type {qtype!r} (question {q.get('id')!r})")
    if not q.get("id") or not q.get("text"):
        raise TypeError("Every question needs an `id` and a `text`")
    base = compact(
        {
            "id": q["id"],
            "type": qtype,
            "text": q["text"],
            "optional": q.get("optional"),
            "level": q.get("level"),
        }
    )
    if qtype in ("single", "multi"):
        base["options"] = list(q.get("options") or [])
        if q.get("allow_other") is not None:
            base["allow_other"] = q["allow_other"]
    return base


def ask_to_wire(
    *,
    title: str,
    questions: Sequence[Mapping[str, Any]],
    to: Union[str, Iterable[str], None] = None,
    context: Optional[str] = None,
    level: Optional[int] = None,
    severity: Optional[str] = None,
    quorum: Optional[int] = None,
    decision: Optional[Mapping[str, Any]] = None,
    offline: Optional[str] = None,
    timeout: Optional[Duration] = None,
    on_timeout: Optional[Mapping[str, Any]] = None,
    escalate: Optional[Iterable[Union[str, Mapping[str, Any]]]] = None,
    callback_url: Optional[str] = None,
    require_signature: Optional[bool] = None,
    card: Optional[Mapping[str, Any]] = None,
) -> Dict[str, Any]:
    """``ask()`` arguments → ``question-set.schema.json``.

    ``severity``: ``"high"`` (default) or ``"critical"`` (the person's app may take
    the full screen with an alarm until they decide). ``require_signature``: ask
    for the person's electronic signature on top of the level (Pro plans and above).

    ``decision``: who may decide and which rule closes the question, e.g.
    ``{"by": "#board>=director", "rule": "quorum", "required": 2, "requested_by": "@ana"}``
    (rules: ``any``, ``quorum``, ``majority``, ``unanimous``, ``each_group``; ``veto``,
    ``question`` and ``approve_options`` are optional). ``escalate``: steps
    ``{"after": "30m", "to": "#ceo"}`` (a plain string escalates at half the timeout).
    """
    return compact(
        {
            "version": "0",
            "to": _list(to),
            "title": title,
            "context": context,
            "questions": [question_to_wire(q) for q in questions],
            "level": level,
            "severity": severity,
            "quorum": {"required": quorum} if quorum is not None else None,
            "decision": _decision_to_wire(decision) if decision is not None else None,
            "offline": offline,
            "timeout": duration(timeout),
            "on_timeout": dict(on_timeout) if on_timeout is not None else None,
            "escalate": [_escalation_to_wire(s) for s in escalate] if escalate is not None else None,
            "callback_url": callback_url,
            "require_signature": True if require_signature else None,
            "card": dict(card) if card is not None else None,
        }
    )


def _decision_to_wire(d: Mapping[str, Any]) -> Dict[str, Any]:
    out = compact(dict(d))
    if "by" in out:
        out["by"] = _list(out["by"])
    return out


def _escalation_to_wire(s: Union[str, Mapping[str, Any]]) -> Any:
    if isinstance(s, str):
        return s
    return compact({"after": duration(s.get("after")), "to": _list(s.get("to"))})


def ask_info_from_wire(v: Mapping[str, Any]) -> AskInfo:
    return AskInfo(
        id=v["id"],
        status=v["status"],
        version=v.get("version", 0),
        level=v.get("level", 0),
        title=v.get("title", ""),
        created_at=v.get("created_at", ""),
        timeout_at=v.get("timeout_at"),
        recipients=v.get("recipients"),
        notification_id=v.get("notification_id"),
        content_hash=v.get("content_hash"),
        poll_url=v.get("poll_url"),
        reply=v.get("reply"),
        decision=v.get("decision"),
    )


def member_token_to_wire(
    *,
    email: str,
    external_id: Optional[str] = None,
    display_name: Optional[str] = None,
    ttl: Optional[Duration] = None,
) -> Dict[str, Any]:
    """``issue_token()`` arguments → ``POST /v0/member-tokens`` body."""
    return compact(
        {"email": email, "external_id": external_id, "display_name": display_name, "ttl": duration(ttl)}
    )


def member_token_from_wire(r: Mapping[str, Any]) -> MemberToken:
    u = r.get("user") or {}
    return MemberToken(
        token=r["token"],
        token_type=r.get("token_type", "Bearer"),
        expires_at=r.get("expires_at", ""),
        workspace_id=r.get("workspace_id", ""),
        user=MemberUser(
            id=u.get("id", ""),
            email=u.get("email", ""),
            handle=u.get("handle", ""),
            display_name=u.get("display_name", ""),
            locale=u.get("locale", ""),
            created_at=u.get("created_at", ""),
            avatar_url=u.get("avatar_url") or None,
        ),
        membership_created=bool(r.get("membership_created", False)),
    )


def _answer_value(a: Mapping[str, Any], q: Optional[Mapping[str, Any]]) -> Any:
    free_raw = a.get("free_text")
    free = UntrustedText(free_raw) if isinstance(free_raw, str) and free_raw != "" else None
    value = a.get("value")
    audio_ref = a.get("audio_ref")
    qtype = q.get("type") if q else None
    if qtype == "text":
        if free is not None:
            return free
        return UntrustedText(value) if isinstance(value, str) else None
    if qtype == "voice":
        return VoiceAnswer(text=free, audio_ref=audio_ref) if free or audio_ref else None
    if qtype == "single":
        return value if value is not None else free
    if qtype == "multi":
        if value is not None:
            return value
        return [] if free else None
    if qtype in ("yesno", "number", "date"):
        return value
    # Unknown question (e.g. a webhook parsed without the questions).
    if value is not None:
        return value
    if free is not None:
        return free
    if audio_ref:
        return VoiceAnswer(audio_ref=audio_ref)
    return None


def to_ask_result(reply: Any, questions: Optional[Sequence[Mapping[str, Any]]] = None) -> AskResult:
    """Turns a reply (``reply.schema.json``, from ``GET /v0/asks/{id}`` or the ``reply``
    webhook) into an ``AskResult``. Pass the questions used in ``ask()`` to get the
    answers converted per question type."""
    if not isinstance(reply, Mapping) or not isinstance(reply.get("answers"), list):
        raise TypeError("Not a notific.me reply: expected an object with `answers`")
    by_id = {q["id"]: q for q in (questions or [])}
    answers: Dict[str, Any] = {qid: None for qid in by_id}
    details: Dict[str, AnswerDetail] = {}
    for a in reply["answers"]:
        qid = a["question_id"]
        free_raw = a.get("free_text")
        details[qid] = AnswerDetail(
            question_id=qid,
            question_text=a.get("question_text", ""),
            value=a.get("value"),
            free_text=UntrustedText(free_raw) if isinstance(free_raw, str) and free_raw else None,
            audio_ref=a.get("audio_ref") or None,
        )
        answers[qid] = _answer_value(a, by_id.get(qid))

    by = reply.get("answered_by")
    sig = reply.get("signature")
    es = reply.get("e_signature")
    status = reply.get("status", "")
    return AskResult(
        id=reply.get("question_set_id", ""),
        version=reply.get("version", 0),
        status=status,
        timed_out=status == "timeout",
        as_text=reply.get("as_text") or "",
        answers=answers,
        details=details,
        raw=dict(reply),
        answered_by=AnsweredBy(
            identity=by.get("identity", ""),
            device_id=by.get("device_id", ""),
            level_used=by.get("level_used", 0),
            auth_method=by.get("auth_method"),
            group=by.get("group"),
            authority_level=by.get("authority_level"),
        )
        if isinstance(by, Mapping)
        else None,
        answered_at=reply.get("answered_at"),
        content_hash=reply.get("content_hash"),
        e_signature=ESignature(
            name=es.get("name", ""),
            initials=es.get("initials", ""),
            method=es.get("method", ""),
            signature_png=es.get("signature_png", ""),
            signature_sha256=es.get("signature_sha256", ""),
            initials_png=es.get("initials_png", ""),
            initials_sha256=es.get("initials_sha256", ""),
            adopted_at=es.get("adopted_at", ""),
            style=es.get("style"),
            consent_version=es.get("consent_version"),
        )
        if isinstance(es, Mapping)
        else None,
        signature=ReplySignature(alg=sig.get("alg", ""), value=sig.get("value", ""), key_id=sig.get("key_id"))
        if isinstance(sig, Mapping)
        else None,
        decision=reply.get("decision"),
        votes=reply.get("votes"),
    )
