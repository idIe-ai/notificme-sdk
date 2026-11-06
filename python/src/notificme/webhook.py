"""Verification of webhooks sent by the notific.me server (signed replies and other topics).

::

    Notificme-Signature: t=<unix>,kid=<key id>,sig=<base64url(Ed25519(t + "." + body))>
    Notificme-Webhook-Id: <id>  (same on every attempt: use it to deduplicate)
    Notificme-Topic: <topic>    (e.g. "reply")
    Notificme-Attempt: <n>

The public key is published at ``GET <base_url>/.well-known/notificme-keys.json``
(JWKS with OKP/Ed25519 keys).
"""

from __future__ import annotations

import base64
import binascii
import json
import re
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple, Union

import httpx
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from .errors import WebhookVerificationError
from .types import VerifiedSignature, Webhook
from .wire import to_ask_result

WEBHOOK_HEADERS = {
    "signature": "Notificme-Signature",
    "webhook_id": "Notificme-Webhook-Id",
    "topic": "Notificme-Topic",
    "attempt": "Notificme-Attempt",
}

#: Default accepted clock skew between the server and you: 5 minutes.
DEFAULT_TOLERANCE_SECONDS = 300

#: Path of the server's public keys (JWKS).
KEYS_PATH = "/.well-known/notificme-keys.json"

_JWKS_TTL = 600.0
_JWKS_REFRESH_MIN = 30.0
_B64 = re.compile(r"^[A-Za-z0-9+/]*$")


@dataclass(frozen=True)
class KeysUrl:
    """Fetch (and cache) the server's public keys from this URL.

    Pass ``http_client`` / ``async_http_client`` to reuse your own httpx clients.
    """

    url: str
    http_client: Optional[httpx.Client] = None
    async_http_client: Optional[httpx.AsyncClient] = None


#: The server's public key: a keys URL (``str`` starting with http(s) or ``KeysUrl``),
#: the raw 32-byte key, its base64url/base64 string, a JWK or a JWKS dict.
WebhookKey = Union[str, bytes, Mapping[str, Any], KeysUrl]
RawBody = Union[str, bytes, bytearray, memoryview]
HeadersLike = Mapping[str, Any]

_cache: Dict[str, Tuple[List[Dict[str, Any]], float]] = {}
_lock = threading.Lock()


def clear_webhook_key_cache() -> None:
    """Clears the cache of keys fetched from a keys URL (useful in tests)."""
    with _lock:
        _cache.clear()


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _b64(s: str) -> Optional[bytes]:
    clean = s.strip().rstrip("=").replace("-", "+").replace("_", "/")
    if not _B64.match(clean) or len(clean) % 4 == 1:
        return None
    try:
        return base64.b64decode(clean + "=" * (-len(clean) % 4))
    except (binascii.Error, ValueError):
        return None


def _to_bytes(body: RawBody) -> bytes:
    if isinstance(body, str):
        return body.encode("utf-8")
    if isinstance(body, (bytes, bytearray, memoryview)):
        return bytes(body)
    raise TypeError("raw_body must be str, bytes, bytearray or memoryview")


def get_header(headers: Optional[HeadersLike], name: str) -> Optional[str]:
    """Case-insensitive header lookup on any mapping (dict, httpx, Starlette, Flask…)."""
    if not headers:
        return None
    v = headers.get(name)
    if v is None:
        v = headers.get(name.lower())
    if v is None:
        lower = name.lower()
        for k in headers.keys():
            if isinstance(k, str) and k.lower() == lower:
                v = headers[k]
                break
    if isinstance(v, (list, tuple)):
        v = v[0] if v else None
    if isinstance(v, bytes):
        v = v.decode("latin-1")
    return v


def _is_jwk(v: Any) -> bool:
    return (
        isinstance(v, Mapping)
        and v.get("kty") == "OKP"
        and v.get("crv") == "Ed25519"
        and isinstance(v.get("x"), str)
    )


def _jwks_keys(doc: Any) -> List[Dict[str, Any]]:
    if isinstance(doc, Mapping) and isinstance(doc.get("keys"), list):
        return [dict(k) for k in doc["keys"] if _is_jwk(k)]
    if _is_jwk(doc):
        return [dict(doc)]
    return []


def _pick(keys: Sequence[Mapping[str, Any]], kid: Optional[str]) -> Optional[Mapping[str, Any]]:
    if kid:
        for k in keys:
            if k.get("kid") == kid:
                return k
        # A key without kid matches any header.
        return keys[0] if len(keys) == 1 and not keys[0].get("kid") else None
    return keys[0] if keys else None


def _jwk_bytes(jwk: Mapping[str, Any]) -> bytes:
    raw = _b64(jwk["x"])
    if raw is None or len(raw) != 32:
        raise WebhookVerificationError("invalid_key", "Public key is not a 32-byte Ed25519 key")
    return raw


def _static_key(key: WebhookKey, kid: Optional[str]) -> Union[bytes, KeysUrl]:
    """Resolves a key that needs no download, or returns the ``KeysUrl`` to fetch."""
    if isinstance(key, KeysUrl):
        return key
    if isinstance(key, (bytes, bytearray)):
        if len(key) != 32:
            raise WebhookVerificationError("invalid_key", "Public key must have 32 bytes")
        return bytes(key)
    if isinstance(key, str):
        if re.match(r"^https?://", key, re.I):
            return KeysUrl(key)
        raw = _b64(key)
        if raw is None or len(raw) != 32:
            raise WebhookVerificationError("invalid_key", "Public key must be the base64url of 32 bytes")
        return raw
    keys = _jwks_keys(key)
    if not keys:
        raise WebhookVerificationError("invalid_key", "Unsupported public key format")
    jwk = _pick(keys, kid)
    if jwk is None:
        raise WebhookVerificationError("unknown_key", f"No public key with kid {json.dumps(kid)}")
    return _jwk_bytes(jwk)


def _cached(url: str) -> Optional[Tuple[List[Dict[str, Any]], float]]:
    with _lock:
        return _cache.get(url)


def _store(url: str, res: httpx.Response) -> List[Dict[str, Any]]:
    if not res.is_success:
        raise WebhookVerificationError(
            "unknown_key", f"Could not download the keys from {url}: HTTP {res.status_code}"
        )
    try:
        keys = _jwks_keys(res.json())
    except ValueError as err:
        raise WebhookVerificationError("unknown_key", f"Keys at {url} are not JSON") from err
    with _lock:
        _cache[url] = (keys, time.monotonic())
    return keys


def _fetch_sync(k: KeysUrl) -> List[Dict[str, Any]]:
    try:
        if k.http_client is not None:
            res = k.http_client.get(k.url, headers={"Accept": "application/json"})
        else:
            with httpx.Client() as c:
                res = c.get(k.url, headers={"Accept": "application/json"})
    except httpx.HTTPError as err:
        raise WebhookVerificationError(
            "unknown_key", f"Could not download the keys from {k.url}: {err}"
        ) from err
    return _store(k.url, res)


async def _fetch_async(k: KeysUrl) -> List[Dict[str, Any]]:
    try:
        if k.async_http_client is not None:
            res = await k.async_http_client.get(k.url, headers={"Accept": "application/json"})
        else:
            async with httpx.AsyncClient() as c:
                res = await c.get(k.url, headers={"Accept": "application/json"})
    except httpx.HTTPError as err:
        raise WebhookVerificationError(
            "unknown_key", f"Could not download the keys from {k.url}: {err}"
        ) from err
    return _store(k.url, res)


def _need_fetch(url: str) -> bool:
    c = _cached(url)
    return c is None or time.monotonic() - c[1] > _JWKS_TTL


def _pick_cached(url: str, kid: Optional[str]) -> Tuple[Optional[Mapping[str, Any]], bool]:
    """Returns (key, may_refresh)."""
    c = _cached(url)
    assert c is not None
    jwk = _pick(c[0], kid)
    return jwk, jwk is None and time.monotonic() - c[1] > _JWKS_REFRESH_MIN


def _unknown(url: str, kid: Optional[str]) -> WebhookVerificationError:
    return WebhookVerificationError("unknown_key", f"No public key with kid {json.dumps(kid)} at {url}")


def _resolve_sync(key: WebhookKey, kid: Optional[str]) -> bytes:
    k = _static_key(key, kid)
    if isinstance(k, bytes):
        return k
    if _need_fetch(k.url):
        _fetch_sync(k)
    jwk, refresh = _pick_cached(k.url, kid)
    if refresh:  # Unknown kid: the server may have rotated its key.
        _fetch_sync(k)
        jwk, _ = _pick_cached(k.url, kid)
    if jwk is None:
        raise _unknown(k.url, kid)
    return _jwk_bytes(jwk)


async def _resolve_async(key: WebhookKey, kid: Optional[str]) -> bytes:
    k = _static_key(key, kid)
    if isinstance(k, bytes):
        return k
    if _need_fetch(k.url):
        await _fetch_async(k)
    jwk, refresh = _pick_cached(k.url, kid)
    if refresh:
        await _fetch_async(k)
        jwk, _ = _pick_cached(k.url, kid)
    if jwk is None:
        raise _unknown(k.url, kid)
    return _jwk_bytes(jwk)


# ---------------------------------------------------------------------------
# verification
# ---------------------------------------------------------------------------


def parse_signature_header(header: str) -> Optional[Dict[str, str]]:
    """Parses ``t=…,kid=…,sig=…`` (unknown parts are ignored)."""
    out: Dict[str, str] = {}
    for part in header.split(","):
        p = part.strip()
        k, sep, v = p.partition("=")
        if sep and k in ("t", "kid", "sig"):
            out[k] = v
    if not out.get("t") or not out.get("sig"):
        return None
    if not out.get("kid"):
        out.pop("kid", None)
    return out


def _check(
    signature_header: Optional[str], tolerance_seconds: float, now: Union[datetime, float, None]
) -> Tuple[Dict[str, str], int, bytes]:
    name = WEBHOOK_HEADERS["signature"]
    if not signature_header:
        raise WebhookVerificationError("missing_header", f"Missing {name} header")
    parsed = parse_signature_header(signature_header)
    if parsed is None:
        raise WebhookVerificationError("malformed_header", f"Malformed {name} header")
    if not re.fullmatch(r"-?\d+", parsed["t"]):
        raise WebhookVerificationError("malformed_header", "Invalid signature timestamp")
    timestamp = int(parsed["t"])
    if tolerance_seconds > 0:
        if now is None:
            now_s = time.time()
        elif isinstance(now, datetime):
            now_s = now.timestamp()
        else:
            now_s = float(now)
        if abs(now_s - timestamp) > tolerance_seconds:
            raise WebhookVerificationError(
                "timestamp_out_of_tolerance", "Signature timestamp is outside the tolerance window"
            )
    sig = _b64(parsed["sig"])
    if sig is None or len(sig) != 64:
        raise WebhookVerificationError("invalid_signature", "Signature is not a base64url Ed25519 signature")
    return parsed, timestamp, sig


def _verify(
    pub: bytes, parsed: Dict[str, str], timestamp: int, sig: bytes, body: RawBody
) -> VerifiedSignature:
    msg = (parsed["t"] + ".").encode("utf-8") + _to_bytes(body)
    try:
        Ed25519PublicKey.from_public_bytes(pub).verify(sig, msg)
    except InvalidSignature:
        raise WebhookVerificationError("invalid_signature", "Signature does not match the body") from None
    except ValueError as err:
        raise WebhookVerificationError("invalid_key", f"Could not verify the signature: {err}") from err
    return VerifiedSignature(timestamp=timestamp, key_id=parsed.get("kid"))


def verify_webhook(
    raw_body: RawBody,
    signature_header: Optional[str],
    key: WebhookKey,
    *,
    tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
    now: Union[datetime, float, None] = None,
) -> VerifiedSignature:
    """Verifies the ``Notificme-Signature`` header against the exact raw body.

    Raises ``WebhookVerificationError`` when the header is missing or malformed, the
    timestamp is outside the tolerance window (``0`` disables the check), the key is
    unknown, or the signature does not match. Always pass the raw bytes as received:
    a re-serialized JSON body will not match.
    """
    parsed, timestamp, sig = _check(signature_header, tolerance_seconds, now)
    pub = _resolve_sync(key, parsed.get("kid"))
    return _verify(pub, parsed, timestamp, sig, raw_body)


async def averify_webhook(
    raw_body: RawBody,
    signature_header: Optional[str],
    key: WebhookKey,
    *,
    tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
    now: Union[datetime, float, None] = None,
) -> VerifiedSignature:
    """Async ``verify_webhook()`` (downloads the keys without blocking the event loop)."""
    parsed, timestamp, sig = _check(signature_header, tolerance_seconds, now)
    pub = await _resolve_async(key, parsed.get("kid"))
    return _verify(pub, parsed, timestamp, sig, raw_body)


def _build(
    verified: VerifiedSignature,
    raw_body: RawBody,
    headers: HeadersLike,
    questions: Optional[Sequence[Mapping[str, Any]]],
) -> Webhook:
    text = raw_body if isinstance(raw_body, str) else _to_bytes(raw_body).decode("utf-8")
    try:
        payload = json.loads(text) if text else None
    except ValueError:
        payload = None
    attempt = get_header(headers, WEBHOOK_HEADERS["attempt"])
    topic = get_header(headers, WEBHOOK_HEADERS["topic"])
    hook = Webhook(
        timestamp=verified.timestamp,
        key_id=verified.key_id,
        id=get_header(headers, WEBHOOK_HEADERS["webhook_id"]),
        topic=topic,
        attempt=int(attempt) if attempt is not None and attempt.isdigit() else None,
        raw_body=text,
        payload=payload,
    )
    if topic == "reply" and isinstance(payload, dict) and isinstance(payload.get("answers"), list):
        hook.reply = to_ask_result(payload, questions)
    return hook


def receive_webhook(
    raw_body: RawBody,
    headers: HeadersLike,
    key: WebhookKey,
    *,
    questions: Optional[Sequence[Mapping[str, Any]]] = None,
    tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
    now: Union[datetime, float, None] = None,
) -> Webhook:
    """Verifies a webhook and parses it: headers, JSON payload and, for the ``reply``
    topic, an ``AskResult`` (pass ``questions`` to convert the answers per type)."""
    sig = get_header(headers, WEBHOOK_HEADERS["signature"])
    verified = verify_webhook(raw_body, sig, key, tolerance_seconds=tolerance_seconds, now=now)
    return _build(verified, raw_body, headers, questions)


async def areceive_webhook(
    raw_body: RawBody,
    headers: HeadersLike,
    key: WebhookKey,
    *,
    questions: Optional[Sequence[Mapping[str, Any]]] = None,
    tolerance_seconds: float = DEFAULT_TOLERANCE_SECONDS,
    now: Union[datetime, float, None] = None,
) -> Webhook:
    """Async ``receive_webhook()``."""
    sig = get_header(headers, WEBHOOK_HEADERS["signature"])
    verified = await averify_webhook(raw_body, sig, key, tolerance_seconds=tolerance_seconds, now=now)
    return _build(verified, raw_body, headers, questions)
