"""HTTP layer: auth, timeouts and automatic retries (429, 5xx and network errors)."""

from __future__ import annotations

import asyncio
import json
import math
import random
import time
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from typing import Any, Dict, Mapping, Optional, Tuple

import httpx

from .errors import NotificMeApiError, NotificMeError

#: Statuses retried automatically.
RETRY_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})


@dataclass
class HttpConfig:
    base_url: str
    token: str
    timeout: float
    max_retries: int
    retry_base_delay: float
    retry_max_delay: float
    user_agent: str


def parse_retry_after(value: Optional[str], now: Optional[float] = None) -> Optional[float]:
    """Parses ``Retry-After`` (seconds or HTTP date) into seconds."""
    if not value:
        return None
    try:
        n = float(value)
        if math.isfinite(n):
            return max(0.0, n)
    except ValueError:
        pass
    try:
        at = float(parsedate_to_datetime(value).timestamp())
    except (TypeError, ValueError, IndexError):
        return None
    return max(0.0, at - (time.time() if now is None else now))


def backoff(cfg: HttpConfig, attempt: int, retry_after: Optional[float] = None) -> float:
    if retry_after is not None:
        return min(retry_after, max(cfg.retry_max_delay, 60.0))
    exp: float = min(cfg.retry_max_delay, cfg.retry_base_delay * float(2**attempt))
    # Full jitter between 50% and 100% of the exponential delay.
    return exp / 2 + random.random() * (exp / 2)


def _api_error(res: httpx.Response) -> NotificMeApiError:
    code = f"http_{res.status_code}"
    server_message = None
    details = None
    request_id = res.headers.get("x-request-id")
    try:
        body = res.json() if res.content else None
    except ValueError:
        body = None
    if isinstance(body, dict):
        err = body.get("error")
        if isinstance(err, dict):
            code = err.get("code") or code
            server_message = err.get("message")
            details = err.get("details")
        request_id = body.get("request_id") or request_id
    return NotificMeApiError(
        status=res.status_code,
        code=code,
        server_message=server_message,
        details=details,
        request_id=request_id,
        retry_after=parse_retry_after(res.headers.get("retry-after")),
    )


def _parse_ok(res: httpx.Response, method: str, path: str) -> Any:
    if not res.content:
        return None
    try:
        return res.json()
    except ValueError as err:
        raise NotificMeError("invalid_response", f"Response is not JSON: {method} {path}") from err


class _Base:
    def __init__(self, cfg: HttpConfig) -> None:
        self.cfg = cfg

    def _prepare(
        self,
        method: str,
        path: str,
        body: Any,
        params: Optional[Mapping[str, Any]],
        headers: Optional[Mapping[str, str]],
        timeout: Optional[float],
    ) -> Tuple[str, Dict[str, str], Optional[bytes], Optional[Dict[str, Any]], float]:
        url = self.cfg.base_url + path
        h = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.cfg.token}",
            "User-Agent": self.cfg.user_agent,
        }
        if headers:
            h.update(headers)
        content = None
        if body is not None:
            h["Content-Type"] = "application/json"
            content = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        q = {k: v for k, v in params.items() if v is not None} if params else None
        return url, h, content, q, self.cfg.timeout if timeout is None else timeout

    def _transport_error(self, err: Exception, method: str, path: str, timeout: float) -> NotificMeError:
        if isinstance(err, httpx.TimeoutException):
            e = NotificMeError("timeout", f"Request timed out after {timeout}s: {method} {path}")
        else:
            e = NotificMeError("network_error", f"Network error: {method} {path}: {err}")
        e.__cause__ = err
        return e


class SyncHttp(_Base):
    def __init__(self, cfg: HttpConfig, client: Optional[httpx.Client] = None) -> None:
        super().__init__(cfg)
        self._own = client is None
        self.client = client or httpx.Client()

    def close(self) -> None:
        if self._own:
            self.client.close()

    def request(
        self,
        method: str,
        path: str,
        *,
        body: Any = None,
        params: Optional[Mapping[str, Any]] = None,
        headers: Optional[Mapping[str, str]] = None,
        timeout: Optional[float] = None,
    ) -> Tuple[Any, httpx.Headers]:
        url, h, content, q, t = self._prepare(method, path, body, params, headers, timeout)
        attempt = 0
        while True:
            try:
                res = self.client.request(method, url, headers=h, content=content, params=q, timeout=t)
            except httpx.TransportError as err:
                if attempt < self.cfg.max_retries:
                    time.sleep(backoff(self.cfg, attempt))
                    attempt += 1
                    continue
                raise self._transport_error(err, method, path, t) from err
            if res.is_success:
                return _parse_ok(res, method, path), res.headers
            api_err = _api_error(res)
            if res.status_code in RETRY_STATUS and attempt < self.cfg.max_retries:
                time.sleep(backoff(self.cfg, attempt, api_err.retry_after))
                attempt += 1
                continue
            raise api_err


class AsyncHttp(_Base):
    def __init__(self, cfg: HttpConfig, client: Optional[httpx.AsyncClient] = None) -> None:
        super().__init__(cfg)
        self._own = client is None
        self.client = client or httpx.AsyncClient()

    async def aclose(self) -> None:
        if self._own:
            await self.client.aclose()

    async def request(
        self,
        method: str,
        path: str,
        *,
        body: Any = None,
        params: Optional[Mapping[str, Any]] = None,
        headers: Optional[Mapping[str, str]] = None,
        timeout: Optional[float] = None,
    ) -> Tuple[Any, httpx.Headers]:
        url, h, content, q, t = self._prepare(method, path, body, params, headers, timeout)
        attempt = 0
        while True:
            try:
                res = await self.client.request(method, url, headers=h, content=content, params=q, timeout=t)
            except httpx.TransportError as err:
                if attempt < self.cfg.max_retries:
                    await asyncio.sleep(backoff(self.cfg, attempt))
                    attempt += 1
                    continue
                raise self._transport_error(err, method, path, t) from err
            if res.is_success:
                return _parse_ok(res, method, path), res.headers
            api_err = _api_error(res)
            if res.status_code in RETRY_STATUS and attempt < self.cfg.max_retries:
                await asyncio.sleep(backoff(self.cfg, attempt, api_err.retry_after))
                attempt += 1
                continue
            raise api_err
