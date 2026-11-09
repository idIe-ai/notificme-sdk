"""notific.me clients (sync and async)."""

from __future__ import annotations

import asyncio
import math
import os
import time
from collections import OrderedDict
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, Mapping, Optional, Sequence, Tuple, Union
from urllib.parse import quote

import httpx

from ._http import AsyncHttp, HttpConfig, SyncHttp
from ._version import __version__
from .errors import AskClosedError, AskPendingError, NotificMeError
from .members import AsyncMembers, Members
from .types import AskHandle, AskInfo, AskResult, SendResult, VerifiedSignature, Webhook
from .webhook import (
    DEFAULT_TOLERANCE_SECONDS,
    KEYS_PATH,
    HeadersLike,
    KeysUrl,
    RawBody,
    areceive_webhook,
    averify_webhook,
    receive_webhook,
    verify_webhook,
)
from .wire import (
    Duration,
    ask_info_from_wire,
    ask_to_wire,
    event_to_wire,
    new_id,
    send_result_from_wire,
    to_ask_result,
)

#: Default API address.
DEFAULT_BASE_URL = "https://api.notific.me"

#: Longest wait the server accepts per long-poll request (seconds).
MAX_POLL_WAIT_SECONDS = 60
#: Minimum time between two polls when the server answers early (seconds).
MIN_POLL_INTERVAL = 1.0

_MAX_LIVE_CARDS = 1000

_EVENT_FIELDS = (
    "id",
    "source",
    "type",
    "severity",
    "occurred_at",
    "to",
    "payload",
    "dedup_key",
    "ttl",
    "expires_when",
    "card_id",
    "live",
    "retention",
    "summary",
    "card",
    "content",
)


def _env(name: str) -> Optional[str]:
    v = os.environ.get(name)
    return v or None


class _Base:
    def __init__(
        self,
        token: Optional[str],
        base_url: Optional[str],
        source: Optional[str],
        timeout: float,
        max_retries: int,
        retry_base_delay: float,
        retry_max_delay: float,
        user_agent: Optional[str],
    ) -> None:
        token = token or _env("NOTIFICME_TOKEN")
        if not token:
            raise NotificMeError(
                "missing_token", "A service token is required: pass token= or set NOTIFICME_TOKEN"
            )
        self.base_url = (base_url or _env("NOTIFICME_URL") or DEFAULT_BASE_URL).rstrip("/")
        self.source = source
        ua = f"notificme-sdk-python/{__version__}" + (f" {user_agent}" if user_agent else "")
        self._cfg = HttpConfig(
            base_url=self.base_url,
            token=token,
            timeout=timeout,
            max_retries=max_retries,
            retry_base_delay=retry_base_delay,
            retry_max_delay=retry_max_delay,
            user_agent=ua,
        )
        #: Last event sent per live card, so update() can fill required fields.
        self._live_cards: OrderedDict[str, Dict[str, Any]] = OrderedDict()

    @property
    def keys_url(self) -> str:
        """URL of the server's public keys, used to verify webhooks."""
        return self.base_url + KEYS_PATH

    # -- events -------------------------------------------------------------

    def _prepare_send(self, fields: Dict[str, Any]) -> Tuple[Dict[str, Any], Optional[str], Dict[str, Any]]:
        source = fields.get("source") or self.source
        if not source:
            raise TypeError("Event `source` is required: pass source= here or in the client")
        etype = fields.get("type")
        if not etype:
            raise TypeError("Event `type` is required")
        card_id = fields.get("card_id") or (new_id() if fields.get("live") else None)
        content = fields.get("content") or {}
        body = event_to_wire(
            id=fields.get("id") or new_id(),
            source=source,
            type=etype,
            now=datetime.now(timezone.utc),
            severity=fields.get("severity"),
            occurred_at=fields.get("occurred_at"),
            to=fields.get("to"),
            payload=fields.get("payload"),
            dedup_key=fields.get("dedup_key"),
            ttl=fields.get("ttl"),
            expires_when=fields.get("expires_when"),
            card_id=card_id,
            retention=fields.get("retention"),
            summary=fields.get("summary"),
            card=fields.get("card"),
            content=fields.get("content"),
        )
        base = {
            "source": source,
            "type": etype,
            "severity": fields.get("severity"),
            "summary": fields.get("summary") or content.get("summary"),
        }
        return body, card_id, base

    def _remember(self, card_id: Optional[str], base: Dict[str, Any]) -> None:
        if not card_id:
            return
        self._live_cards.pop(card_id, None)
        self._live_cards[card_id] = base
        while len(self._live_cards) > _MAX_LIVE_CARDS:
            self._live_cards.popitem(last=False)

    def _merge_update(self, card_id: str, fields: Dict[str, Any]) -> Dict[str, Any]:
        if not card_id:
            raise TypeError("update() needs the card_id returned by send()")
        base = self._live_cards.get(card_id, {})
        merged = {k: v for k, v in fields.items() if v is not None}
        merged["type"] = merged.get("type") or base.get("type")
        if not merged["type"]:
            raise TypeError("update(): pass type= (this client has not sent this card before)")
        merged["source"] = merged.get("source") or base.get("source") or self.source
        merged["severity"] = merged.get("severity") or base.get("severity")
        merged["card_id"] = card_id
        merged["live"] = False
        has_card = merged.get("card") or merged.get("content")
        content_summary = (merged.get("content") or {}).get("summary")
        if has_card and merged.get("summary") is None and not content_summary and base.get("summary"):
            merged["summary"] = base["summary"]
        return merged

    # -- asks ---------------------------------------------------------------

    @staticmethod
    def _wait_seconds(deadline: Optional[float], ask_id: str) -> int:
        if deadline is None:
            return MAX_POLL_WAIT_SECONDS
        left = deadline - time.monotonic()
        if left <= 0:
            raise AskPendingError(ask_id)
        return max(1, min(MAX_POLL_WAIT_SECONDS, math.ceil(left)))

    @staticmethod
    def _check_view(
        view: AskInfo, ask_id: str, questions: Optional[Sequence[Mapping[str, Any]]]
    ) -> Optional[AskResult]:
        if view.reply and view.status in ("answered", "timeout"):
            return to_ask_result(view.reply, questions)
        if view.status in ("expired", "cancelled"):
            raise AskClosedError(ask_id, view.status)
        if view.status != "open":
            raise NotificMeError(
                "invalid_response", f"Question set {ask_id} is {view.status} but has no reply"
            )
        return None

    @staticmethod
    def _pause(started: float, deadline: Optional[float], ask_id: str) -> float:
        now = time.monotonic()
        if deadline is not None and now >= deadline:
            raise AskPendingError(ask_id)
        # A proxy may cut the long-poll short: avoid a tight loop.
        pause = MIN_POLL_INTERVAL - (now - started)
        if deadline is not None:
            pause = min(pause, max(0.0, deadline - now))
        return max(0.0, pause)

    def _get_ask_args(
        self, ask_id: str, wait_seconds: float, timeout: Optional[float]
    ) -> Tuple[str, Dict[str, Any], float]:
        wait = max(0, min(MAX_POLL_WAIT_SECONDS, int(wait_seconds)))
        t = timeout if timeout is not None else max(self._cfg.timeout, wait + 15.0)
        return f"/v0/asks/{quote(ask_id, safe='')}", ({"wait": wait} if wait > 0 else {}), t


class NotificMe(_Base):
    """notific.me client.

    ::

        from notificme import NotificMe

        notific = NotificMe(token="nm_svc_...", source="billing")
        notific.send("invoice.failed", severity="high", summary="Invoice #42 failed")

    Use it as a context manager (or call ``close()``) to release connections.
    """

    def __init__(
        self,
        token: Optional[str] = None,
        *,
        base_url: Optional[str] = None,
        source: Optional[str] = None,
        timeout: float = 30.0,
        max_retries: int = 3,
        retry_base_delay: float = 0.5,
        retry_max_delay: float = 10.0,
        user_agent: Optional[str] = None,
        http_client: Optional[httpx.Client] = None,
    ) -> None:
        """
        :param token: service token (``nm_svc_…``). Defaults to ``NOTIFICME_TOKEN``.
        :param base_url: API address. Defaults to ``NOTIFICME_URL`` or ``https://api.notific.me``.
        :param source: default ``source`` of the events (e.g. ``"billing"``).
        :param timeout: per-request timeout in seconds.
        :param max_retries: automatic retries on 429, 5xx and network errors.
        :param retry_base_delay: first retry delay (seconds); doubles on every attempt.
        :param retry_max_delay: maximum retry delay, unless ``Retry-After`` asks for more.
        :param user_agent: extra text appended to the ``User-Agent`` header.
        :param http_client: your own ``httpx.Client`` (proxies, mounts, tests).
        """
        super().__init__(
            token, base_url, source, timeout, max_retries, retry_base_delay, retry_max_delay, user_agent
        )
        self._http = SyncHttp(self._cfg, http_client)
        #: Person tokens for your own app: ``issue_token()`` and ``revoke_tokens()``.
        #: Needs the ``members:token`` scope.
        self.members = Members(self._http)

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> NotificMe:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # -- events -------------------------------------------------------------

    def send(
        self,
        type: str,
        *,
        severity: Optional[str] = None,
        summary: Optional[str] = None,
        card: Optional[Mapping[str, Any]] = None,
        content: Optional[Mapping[str, Any]] = None,
        payload: Optional[Mapping[str, Any]] = None,
        to: Union[str, Iterable[str], None] = None,
        source: Optional[str] = None,
        id: Optional[str] = None,
        occurred_at: Union[datetime, str, None] = None,
        dedup_key: Optional[str] = None,
        ttl: Optional[Duration] = None,
        expires_when: Optional[str] = None,
        card_id: Optional[str] = None,
        live: bool = False,
        retention: Optional[str] = None,
        request_timeout: Optional[float] = None,
    ) -> SendResult:
        """Sends an event (``POST /v0/events``).

        Retries are safe: the SDK sets an idempotency ``id`` when you do not pass one.
        ``live=True`` (without ``card_id``) creates a live card; pass the returned
        ``card_id`` to ``update()``.
        """
        fields = {k: v for k, v in locals().items() if k in _EVENT_FIELDS}
        body, cid, base = self._prepare_send(fields)
        data, headers = self._http.request("POST", "/v0/events", body=body, timeout=request_timeout)
        self._remember(cid, base)
        return send_result_from_wire(data, cid, headers.get("idempotent-replayed") == "true")

    def update(self, card_id: str, *, request_timeout: Optional[float] = None, **fields: Any) -> SendResult:
        """Updates a live card: sends a new event with the same ``card_id``.

        Fields you omit (``type``, ``severity``, ``source``, ``summary``) are taken from
        the last event this client sent for that card. Accepts the arguments of ``send()``.
        """
        merged = self._merge_update(card_id, fields)
        return self.send(merged.pop("type"), request_timeout=request_timeout, **merged)

    # -- asks ---------------------------------------------------------------

    def ask(
        self,
        title: str,
        questions: Sequence[Mapping[str, Any]],
        *,
        max_wait: Optional[float] = None,
        idempotency_key: Optional[str] = None,
        request_timeout: Optional[float] = None,
        **options: Any,
    ) -> AskResult:
        """Asks humans and waits (long-poll) for the decision.

        Returns the signed reply when answered, or when it times out (``status ==
        "timeout"``, with ``on_timeout`` answers applied). Raises ``AskClosedError``
        when the question set expires or is cancelled, and ``AskPendingError`` when
        ``max_wait`` (seconds) elapses first.

        ``options``: ``to``, ``context``, ``level``, ``severity`` (``"critical"`` may take the
        person's screen), ``quorum``, ``decision`` (who decides and the rule, e.g.
        ``{"by": "#board>=director", "rule": "quorum", "required": 2}``), ``offline``,
        ``timeout`` (e.g. ``"30m"``), ``on_timeout``, ``escalate`` (e.g.
        ``[{"after": "30m", "to": "#ceo"}]``), ``callback_url``, ``require_signature`` (the
        person's electronic signature, in ``AskResult.e_signature``; Pro plans and above),
        ``card``. ``to`` accepts ``@handle``, member e-mails and groups (``#finance``,
        ``#finance>=manager``).
        """
        handle = self.create_ask(
            title, questions, idempotency_key=idempotency_key, request_timeout=request_timeout, **options
        )
        return self.wait_for_reply(handle.id, questions=questions, max_wait=max_wait)

    def create_ask(
        self,
        title: str,
        questions: Sequence[Mapping[str, Any]],
        *,
        idempotency_key: Optional[str] = None,
        request_timeout: Optional[float] = None,
        **options: Any,
    ) -> AskHandle:
        """Creates the question set (``POST /v0/asks``) without waiting for the reply."""
        if not questions:
            raise TypeError("ask() needs at least one question")
        data, _ = self._http.request(
            "POST",
            "/v0/asks",
            body=ask_to_wire(title=title, questions=questions, **options),
            headers={"Idempotency-Key": idempotency_key or new_id()},
            timeout=request_timeout,
        )
        info = ask_info_from_wire(data)
        qs = list(questions)

        def wait(*, max_wait: Optional[float] = None) -> AskResult:
            return self.wait_for_reply(info.id, questions=qs, max_wait=max_wait)

        return AskHandle(**vars(info), _wait=wait)

    def get_ask(
        self, ask_id: str, *, wait_seconds: float = 0, request_timeout: Optional[float] = None
    ) -> AskInfo:
        """Reads a question set (``GET /v0/asks/{id}``), optionally waiting up to
        ``wait_seconds`` (max 60) for a change."""
        path, params, t = self._get_ask_args(ask_id, wait_seconds, request_timeout)
        data, _ = self._http.request("GET", path, params=params, timeout=t)
        return ask_info_from_wire(data)

    def wait_for_reply(
        self,
        ask_id: str,
        *,
        questions: Optional[Sequence[Mapping[str, Any]]] = None,
        max_wait: Optional[float] = None,
    ) -> AskResult:
        """Waits (long-poll) for the reply of an existing question set. Pass the
        ``questions`` used to create it to get the answers converted per type."""
        deadline = time.monotonic() + max_wait if max_wait is not None else None
        while True:
            wait = self._wait_seconds(deadline, ask_id)
            started = time.monotonic()
            result = self._check_view(self.get_ask(ask_id, wait_seconds=wait), ask_id, questions)
            if result is not None:
                return result
            pause = self._pause(started, deadline, ask_id)
            if pause > 0:
                time.sleep(pause)

    # -- webhooks -----------------------------------------------------------

    def verify_webhook(
        self,
        raw_body: RawBody,
        signature_header: Optional[str],
        *,
        tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
        now: Union[datetime, float, None] = None,
    ) -> VerifiedSignature:
        """``verify_webhook()`` using this server's public keys (``keys_url``)."""
        key = KeysUrl(self.keys_url, http_client=self._http.client)
        return verify_webhook(raw_body, signature_header, key, tolerance_seconds=tolerance_seconds, now=now)

    def receive_webhook(
        self,
        raw_body: RawBody,
        headers: HeadersLike,
        *,
        questions: Optional[Sequence[Mapping[str, Any]]] = None,
        tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
        now: Union[datetime, float, None] = None,
    ) -> Webhook:
        """``receive_webhook()`` using this server's public keys (``keys_url``)."""
        key = KeysUrl(self.keys_url, http_client=self._http.client)
        return receive_webhook(
            raw_body, headers, key, questions=questions, tolerance_seconds=tolerance_seconds, now=now
        )


class AsyncNotificMe(_Base):
    """Async notific.me client (``asyncio``). Same API as ``NotificMe``, with ``await``.

    ::

        async with AsyncNotificMe(source="agent") as notific:
            result = await notific.ask("Deploy?", [{"id": "ok", "type": "yesno", "text": "Ship it?"}])
    """

    def __init__(
        self,
        token: Optional[str] = None,
        *,
        base_url: Optional[str] = None,
        source: Optional[str] = None,
        timeout: float = 30.0,
        max_retries: int = 3,
        retry_base_delay: float = 0.5,
        retry_max_delay: float = 10.0,
        user_agent: Optional[str] = None,
        http_client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        super().__init__(
            token, base_url, source, timeout, max_retries, retry_base_delay, retry_max_delay, user_agent
        )
        self._http = AsyncHttp(self._cfg, http_client)
        #: Person tokens for your own app (``await notific.members.issue_token(...)``).
        self.members = AsyncMembers(self._http)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def __aenter__(self) -> AsyncNotificMe:
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()

    async def send(
        self,
        type: str,
        *,
        severity: Optional[str] = None,
        summary: Optional[str] = None,
        card: Optional[Mapping[str, Any]] = None,
        content: Optional[Mapping[str, Any]] = None,
        payload: Optional[Mapping[str, Any]] = None,
        to: Union[str, Iterable[str], None] = None,
        source: Optional[str] = None,
        id: Optional[str] = None,
        occurred_at: Union[datetime, str, None] = None,
        dedup_key: Optional[str] = None,
        ttl: Optional[Duration] = None,
        expires_when: Optional[str] = None,
        card_id: Optional[str] = None,
        live: bool = False,
        retention: Optional[str] = None,
        request_timeout: Optional[float] = None,
    ) -> SendResult:
        """Sends an event (``POST /v0/events``). See ``NotificMe.send``."""
        fields = {k: v for k, v in locals().items() if k in _EVENT_FIELDS}
        body, cid, base = self._prepare_send(fields)
        data, headers = await self._http.request("POST", "/v0/events", body=body, timeout=request_timeout)
        self._remember(cid, base)
        return send_result_from_wire(data, cid, headers.get("idempotent-replayed") == "true")

    async def update(
        self, card_id: str, *, request_timeout: Optional[float] = None, **fields: Any
    ) -> SendResult:
        """Updates a live card. See ``NotificMe.update``."""
        merged = self._merge_update(card_id, fields)
        return await self.send(merged.pop("type"), request_timeout=request_timeout, **merged)

    async def ask(
        self,
        title: str,
        questions: Sequence[Mapping[str, Any]],
        *,
        max_wait: Optional[float] = None,
        idempotency_key: Optional[str] = None,
        request_timeout: Optional[float] = None,
        **options: Any,
    ) -> AskResult:
        """Asks humans and waits for the decision. See ``NotificMe.ask``."""
        handle = await self.create_ask(
            title, questions, idempotency_key=idempotency_key, request_timeout=request_timeout, **options
        )
        return await self.wait_for_reply(handle.id, questions=questions, max_wait=max_wait)

    async def create_ask(
        self,
        title: str,
        questions: Sequence[Mapping[str, Any]],
        *,
        idempotency_key: Optional[str] = None,
        request_timeout: Optional[float] = None,
        **options: Any,
    ) -> AskHandle:
        """Creates the question set without waiting. ``await handle.wait()`` for the reply."""
        if not questions:
            raise TypeError("ask() needs at least one question")
        data, _ = await self._http.request(
            "POST",
            "/v0/asks",
            body=ask_to_wire(title=title, questions=questions, **options),
            headers={"Idempotency-Key": idempotency_key or new_id()},
            timeout=request_timeout,
        )
        info = ask_info_from_wire(data)
        qs = list(questions)

        def wait(*, max_wait: Optional[float] = None) -> Any:
            return self.wait_for_reply(info.id, questions=qs, max_wait=max_wait)

        return AskHandle(**vars(info), _wait=wait)

    async def get_ask(
        self, ask_id: str, *, wait_seconds: float = 0, request_timeout: Optional[float] = None
    ) -> AskInfo:
        """Reads a question set, optionally long-polling up to ``wait_seconds`` (max 60)."""
        path, params, t = self._get_ask_args(ask_id, wait_seconds, request_timeout)
        data, _ = await self._http.request("GET", path, params=params, timeout=t)
        return ask_info_from_wire(data)

    async def wait_for_reply(
        self,
        ask_id: str,
        *,
        questions: Optional[Sequence[Mapping[str, Any]]] = None,
        max_wait: Optional[float] = None,
    ) -> AskResult:
        """Waits (long-poll) for the reply of an existing question set."""
        deadline = time.monotonic() + max_wait if max_wait is not None else None
        while True:
            wait = self._wait_seconds(deadline, ask_id)
            started = time.monotonic()
            view = await self.get_ask(ask_id, wait_seconds=wait)
            result = self._check_view(view, ask_id, questions)
            if result is not None:
                return result
            pause = self._pause(started, deadline, ask_id)
            if pause > 0:
                await asyncio.sleep(pause)

    async def verify_webhook(
        self,
        raw_body: RawBody,
        signature_header: Optional[str],
        *,
        tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
        now: Union[datetime, float, None] = None,
    ) -> VerifiedSignature:
        """``averify_webhook()`` using this server's public keys (``keys_url``)."""
        key = KeysUrl(self.keys_url, async_http_client=self._http.client)
        return await averify_webhook(
            raw_body, signature_header, key, tolerance_seconds=tolerance_seconds, now=now
        )

    async def receive_webhook(
        self,
        raw_body: RawBody,
        headers: HeadersLike,
        *,
        questions: Optional[Sequence[Mapping[str, Any]]] = None,
        tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
        now: Union[datetime, float, None] = None,
    ) -> Webhook:
        """``areceive_webhook()`` using this server's public keys (``keys_url``)."""
        key = KeysUrl(self.keys_url, async_http_client=self._http.client)
        return await areceive_webhook(
            raw_body, headers, key, questions=questions, tolerance_seconds=tolerance_seconds, now=now
        )
