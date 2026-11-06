"""Errors raised by the SDK."""

from __future__ import annotations

from typing import Any, Optional


class NotificMeError(Exception):
    """Base class of every error raised by the SDK.

    ``code`` is stable and machine-readable. For API errors it is the server's
    ``error.code`` (e.g. ``validation_failed``, ``rate_limited``); for client-side
    failures it is one of ``timeout``, ``network_error``, ``invalid_response``,
    ``ask_closed``, ``ask_pending``, ``invalid_signature``, ``missing_token``.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class NotificMeApiError(NotificMeError):
    """The API answered with a non-2xx status."""

    def __init__(
        self,
        *,
        status: int,
        code: str,
        server_message: Optional[str] = None,
        details: Any = None,
        request_id: Optional[str] = None,
        retry_after: Optional[float] = None,
    ) -> None:
        suffix = f": {server_message}" if server_message else ""
        super().__init__(code, f"notific.me API error {status} ({code}){suffix}")
        #: HTTP status code.
        self.status = status
        #: Developer-facing message from the server. It may not be in English.
        self.server_message = server_message
        #: Extra details sent by the server (e.g. field errors of ``validation_failed``).
        self.details = details
        #: Request id echoed by the server (useful when reporting issues).
        self.request_id = request_id
        #: Seconds from the ``Retry-After`` header, when present.
        self.retry_after = retry_after


class AskClosedError(NotificMeError):
    """The question set was closed without a reply (``expired`` or ``cancelled``)."""

    def __init__(self, ask_id: str, status: str) -> None:
        super().__init__("ask_closed", f"Question set {ask_id} was closed without a reply (status: {status})")
        self.ask_id = ask_id
        self.status = status


class AskPendingError(NotificMeError):
    """The client stopped waiting (``max_wait`` elapsed) while the question set is still open.

    Resume later with ``client.wait_for_reply(error.ask_id)``.
    """

    def __init__(self, ask_id: str) -> None:
        super().__init__("ask_pending", f"Stopped waiting for question set {ask_id}; it is still open")
        self.ask_id = ask_id


class WebhookVerificationError(NotificMeError):
    """The ``Notificme-Signature`` header is missing, malformed or does not match the body.

    ``reason`` is one of ``missing_header``, ``malformed_header``,
    ``timestamp_out_of_tolerance``, ``unknown_key``, ``invalid_key``, ``invalid_signature``.
    """

    def __init__(self, reason: str, message: str) -> None:
        super().__init__("invalid_signature", message)
        self.reason = reason
