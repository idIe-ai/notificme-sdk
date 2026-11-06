"""Official Python SDK for notific.me.

- ``NotificMe`` / ``AsyncNotificMe`` clients: ``send()``, ``update()`` (live cards),
  ``ask()`` / ``create_ask()`` / ``wait_for_reply()`` (human decisions),
  ``members.issue_token()`` / ``members.revoke_tokens()`` (person tokens for your own app);
- ``verify_webhook()`` / ``receive_webhook()`` for signed webhooks;
- ``UntrustedText`` for free text typed by humans.
"""

from ._version import __version__
from .client import DEFAULT_BASE_URL, AsyncNotificMe, NotificMe
from .errors import (
    AskClosedError,
    AskPendingError,
    NotificMeApiError,
    NotificMeError,
    WebhookVerificationError,
)
from .members import AsyncMembers, Members
from .types import (
    AnswerDetail,
    AnsweredBy,
    AskHandle,
    AskInfo,
    AskResult,
    AuthMethod,
    Card,
    MemberToken,
    MemberUser,
    Question,
    ReplySignature,
    RevokedTokens,
    SendResult,
    VerifiedSignature,
    VoiceAnswer,
    Webhook,
)
from .untrusted import UntrustedText
from .webhook import (
    DEFAULT_TOLERANCE_SECONDS,
    KEYS_PATH,
    WEBHOOK_HEADERS,
    KeysUrl,
    areceive_webhook,
    averify_webhook,
    clear_webhook_key_cache,
    parse_signature_header,
    receive_webhook,
    verify_webhook,
)
from .wire import to_ask_result

__all__ = [
    "__version__",
    "DEFAULT_BASE_URL",
    "DEFAULT_TOLERANCE_SECONDS",
    "KEYS_PATH",
    "WEBHOOK_HEADERS",
    "AnswerDetail",
    "AnsweredBy",
    "AskClosedError",
    "AskHandle",
    "AskInfo",
    "AskPendingError",
    "AskResult",
    "AsyncMembers",
    "AsyncNotificMe",
    "AuthMethod",
    "Card",
    "KeysUrl",
    "MemberToken",
    "MemberUser",
    "Members",
    "NotificMe",
    "NotificMeApiError",
    "NotificMeError",
    "Question",
    "ReplySignature",
    "RevokedTokens",
    "SendResult",
    "UntrustedText",
    "VerifiedSignature",
    "VoiceAnswer",
    "Webhook",
    "WebhookVerificationError",
    "areceive_webhook",
    "averify_webhook",
    "clear_webhook_key_cache",
    "parse_signature_header",
    "receive_webhook",
    "to_ask_result",
    "verify_webhook",
]
