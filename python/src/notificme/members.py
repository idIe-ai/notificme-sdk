"""Person tokens for your own app (``notific.members``).

Your backend issues a short-lived token for one person; your app uses it to
show that person's inbox and answer decisions, restricted to your workspace.
The service token needs the ``members:token`` scope.
"""

from __future__ import annotations

from typing import Any, Dict, Optional

from ._http import AsyncHttp, SyncHttp
from .types import MemberToken, RevokedTokens
from .wire import Duration, member_token_from_wire, member_token_to_wire

ISSUE_PATH = "/v0/member-tokens"
REVOKE_PATH = "/v0/member-tokens/revoke"


def _issue_body(
    email: str, external_id: Optional[str], display_name: Optional[str], ttl: Optional[Duration]
) -> Dict[str, Any]:
    if not email:
        raise TypeError("issue_token() needs the person's `email`")
    return member_token_to_wire(email=email, external_id=external_id, display_name=display_name, ttl=ttl)


def _revoke_body(email: str) -> Dict[str, Any]:
    if not email:
        raise TypeError("revoke_tokens() needs the person's `email`")
    return {"email": email}


def _revoked(data: Any) -> RevokedTokens:
    return RevokedTokens(revoked=int((data or {}).get("revoked", 0)))


class Members:
    """``notific.members`` on the sync client."""

    def __init__(self, http: SyncHttp) -> None:
        self._http = http

    def issue_token(
        self,
        email: str,
        *,
        external_id: Optional[str] = None,
        display_name: Optional[str] = None,
        ttl: Optional[Duration] = None,
        request_timeout: Optional[float] = None,
    ) -> MemberToken:
        """Issues a person token (``POST /v0/member-tokens``).

        The e-mail becomes a member of your workspace when it is not one yet.

        :param email: e-mail of the person.
        :param external_id: the person's id in your own system.
        :param display_name: name used when the account or the membership is created now.
        :param ttl: lifetime, e.g. ``"30m"``, ``"2h"``, seconds or a ``timedelta``
            (default 1 hour, max 12 hours).
        :raises NotificMeApiError: ``workspace_blocked`` (403) when the person blocked your
            workspace; ``forbidden`` when the service token lacks ``members:token``.
        """
        body = _issue_body(email, external_id, display_name, ttl)
        data, _ = self._http.request("POST", ISSUE_PATH, body=body, timeout=request_timeout)
        return member_token_from_wire(data)

    def revoke_tokens(self, email: str, *, request_timeout: Optional[float] = None) -> RevokedTokens:
        """Revokes every active person token of this e-mail in your workspace
        (``POST /v0/member-tokens/revoke``), e.g. when the person leaves the company."""
        data, _ = self._http.request("POST", REVOKE_PATH, body=_revoke_body(email), timeout=request_timeout)
        return _revoked(data)


class AsyncMembers:
    """``notific.members`` on the async client. Same API as ``Members``, with ``await``."""

    def __init__(self, http: AsyncHttp) -> None:
        self._http = http

    async def issue_token(
        self,
        email: str,
        *,
        external_id: Optional[str] = None,
        display_name: Optional[str] = None,
        ttl: Optional[Duration] = None,
        request_timeout: Optional[float] = None,
    ) -> MemberToken:
        """Issues a person token. See ``Members.issue_token``."""
        body = _issue_body(email, external_id, display_name, ttl)
        data, _ = await self._http.request("POST", ISSUE_PATH, body=body, timeout=request_timeout)
        return member_token_from_wire(data)

    async def revoke_tokens(self, email: str, *, request_timeout: Optional[float] = None) -> RevokedTokens:
        """Revokes every active person token of this e-mail. See ``Members.revoke_tokens``."""
        data, _ = await self._http.request(
            "POST", REVOKE_PATH, body=_revoke_body(email), timeout=request_timeout
        )
        return _revoked(data)
