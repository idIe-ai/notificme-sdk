# notificme

Official Python SDK for [notific.me](https://notific.me). It sends events, keeps live cards up to date, asks people for decisions and waits for the answer, issues person tokens so your own app can show a person's inbox, and verifies the signed webhooks the server sends you.

- Python 3.10+, sync (`NotificMe`) and async (`AsyncNotificMe`) clients.
- Built on [httpx](https://www.python-httpx.org/); webhook signatures use [cryptography](https://cryptography.io/) (Ed25519).
- Retries 429, 5xx and network errors (honouring `Retry-After`), with a timeout on every request.
- Fully typed (`py.typed`).

## Installation

```sh
pip install notificme
```

## Quick start

Create a service token in the customer area. It needs the `events:write`, `asks:write` and `asks:read` scopes. Keep the token on your server.

```python
from notificme import NotificMe

notific = NotificMe(token="nm_svc_...", source="billing")  # or set NOTIFICME_TOKEN

notific.send("invoice.failed", severity="high", summary="Invoice #42 failed")
```

| Argument | Default | |
| --- | --- | --- |
| `token` | `NOTIFICME_TOKEN` env var | Service token (`nm_svc_…`). |
| `base_url` | `NOTIFICME_URL` or `https://api.notific.me` | API address. |
| `source` | none | Default `source` of your events. |
| `timeout` | `30.0` | Per-request timeout (seconds). |
| `max_retries` | `3` | Retries on 429, 5xx and network errors. |
| `retry_base_delay` / `retry_max_delay` | `0.5` / `10.0` | Exponential backoff with jitter (seconds). |
| `http_client` | new `httpx.Client` | Your own client (proxies, mounts, tests). |

Use the client as a context manager (`with NotificMe(...) as notific:`) or call `close()` to release connections.

## Sending events

```python
notific.send(
    "container.die",  # dotted lowercase type
    severity="high",  # info | low | medium | high | critical (default: info)
    summary="api-v2 died on vps-01",
    dedup_key="vps-01/api-v2",  # merges repeats in the dedup window
    expires_when="container.healthy",
    payload={"host": "vps-01", "exit_code": 137},  # sent as is
    card={
        "title": "Container died",
        "blocks": [
            {"type": "metric", "label": "Crashes in 24h", "value": 3},
            {
                "type": "buttons",
                "buttons": [{"label": "Open dashboard", "url": "https://dokploy.example.com"}],
            },
        ],
    },
)
```

Cards and questions use the protocol's snake_case, exactly as in [`protocol/schemas`](https://github.com/idIe-ai/notificme-sdk/tree/main/protocol/schemas). Every event gets an idempotency `id`, so a retried request is not stored twice. You can also pass your own `id`.

### Live cards

```python
deploy = notific.send(
    "deploy.progress",
    summary="Deploying api",
    live=True,  # the SDK creates a card_id; you can also pass your own card_id
    card={"blocks": [{"type": "progress", "value": 0.1}]},
)

notific.update(deploy.card_id, card={"blocks": [{"type": "progress", "value": 0.8}]})
```

`update()` takes `type`, `source`, `severity` and `summary` from the last event this client sent for that card. You can override any of them.

## Asking humans

```python
result = notific.ask(
    "Deploy api to production?",
    [
        {
            "id": "action",
            "type": "single",
            "text": "What should we do?",
            "options": ["Deploy", "Wait"],
            "allow_other": True,
        },
        {"id": "note", "type": "text", "text": "Anything else?", "optional": True},
    ],
    to="ops",
    timeout="30m",
    on_timeout={"action": "Wait"},
)

if result.status == "answered" and result.answers["action"] == "Deploy":
    ...

print(result.as_text)  # natural-language summary, ready for an AI agent
```

`ask()` long-polls until the reply arrives or the question set times out (`result.status == "timeout"`, with `on_timeout` applied). It raises `AskClosedError` when the question set expires or is cancelled. Pass `max_wait=` (seconds) to stop waiting earlier: it raises `AskPendingError`, and you can resume with `notific.wait_for_reply(err.ask_id)`.

Answers are converted per question type: `yesno` → `bool`, `single` → `str`, `multi` → `list[str]`, `number` → number, `date` → `str`, `voice` → `VoiceAnswer`, `text` → `UntrustedText`.

To create the question set without waiting, use `create_ask()` (returns a handle with `.wait()`), or set `callback_url=` and receive the reply on a webhook.

### Untrusted text

Anything a human typed comes back as `UntrustedText`. Never feed it to a shell, SQL, `eval`, HTML or an LLM prompt as instructions. Read it on purpose with `.text`, or embed it as a quoted JSON string with `.quoted()`.

## Person tokens for your own app

Your people may live in your own system (intranet, ERP, help desk) and never open notific.me. With a person token, your app shows their notific.me inbox and lets them answer decisions inside your own screens:

1. The person signs in to your app the way they always do (your SSO, your login).
2. Your **backend** asks notific.me for a short-lived token for that person, using your service token.
3. Your **app** uses that token as `Authorization: Bearer <token>` against the API (inbox, sync, real time and answers), or with the JavaScript client SDK `@notificme/client` once it is published.

The service token needs the `members:token` scope. It is not part of any preset: tick it yourself when you create the token, and keep that token on your server.

```python
# Your backend, after your own login says who the person is (FastAPI).
@app.get("/api/notificme-token")
def notificme_token(user: User = Depends(current_user)):
    t = notific.members.issue_token(
        email=user.email,  # becomes a member of your workspace if it is not one yet
        external_id=user.id,  # optional: the person's id in your system
        display_name=user.name,  # optional: used when the account is created now
        ttl="1h",  # optional: default 1h, max 12h ("30m", "2h", seconds or a timedelta)
    )
    # t: MemberToken(token="nm_dlg_…", token_type="Bearer", expires_at, workspace_id, user, membership_created)
    return {"token": t.token, "expires_at": t.expires_at}


# When the person leaves the company, cut access right away:
notific.members.revoke_tokens(email="ana@acme.com").revoked  # number of tokens revoked
```

What a person token can and cannot do:

- It only sees **your** workspace. Notifications the same person gets from other workspaces never show up in your app.
- It only consumes: inbox, sync, real time and answers. It cannot send events or ask questions.
- It answers decisions up to **level 1** inside your app; those answers are recorded with `auth_method == "delegated"`. Level 2 and above need a proof your company cannot forge (passkey, authenticator code or the notific.me app), so the API answers `403 step_up_required` with `details["level"]`. Level 4 (quorum) always needs the notific.me app.
- It expires (default 1 hour, at most 12). Your app asks your backend for a new one before `expires_at`.

`issue_token()` raises `NotificMeApiError` with code `workspace_blocked` (403) when the person blocked your workspace, and `forbidden` when the service token lacks the `members:token` scope. On `AsyncNotificMe`, use `await notific.members.issue_token(...)`.

## Async

```python
from notificme import AsyncNotificMe

async with AsyncNotificMe(source="agent") as notific:
    await notific.send("agent.started", summary="Agent started")
    result = await notific.ask("Run the migration?", [{"id": "ok", "type": "yesno", "text": "Go?"}])
```

## Verifying webhooks

Every webhook carries a `Notificme-Signature` header signed with Ed25519. Always verify the **raw** body.

```python
# FastAPI
from fastapi import FastAPI, Request
from notificme import NotificMe, WebhookVerificationError

app = FastAPI()
notific = NotificMe(source="billing")


@app.post("/notificme")
async def hook(request: Request):
    body = await request.body()
    try:
        webhook = notific.receive_webhook(body, request.headers)
    except WebhookVerificationError:
        return {"ok": False}, 400
    if webhook.reply:
        print(webhook.reply.answers)
    return {"ok": True}
```

```python
# Flask
from flask import Flask, request
from notificme import NotificMe

app = Flask(__name__)
notific = NotificMe(source="billing")


@app.post("/notificme")
def hook():
    webhook = notific.receive_webhook(request.get_data(), request.headers)
    return {"ok": True}
```

Without a client, pass the key explicitly: `receive_webhook(body, headers, "https://api.notific.me/.well-known/notificme-keys.json")`. The key can also be the base64url public key, raw bytes, a JWK or a JWKS. Keys fetched from a URL are cached for 10 minutes and refreshed when an unknown `kid` shows up.

Use `webhook.id` (`Notificme-Webhook-Id`) to deduplicate: it is the same on every retry.

## Errors

| Error | When |
| --- | --- |
| `NotificMeApiError` | The API answered with a non-2xx status (`status`, `code`, `details`, `request_id`). |
| `AskClosedError` | The question set expired or was cancelled. |
| `AskPendingError` | `max_wait` elapsed while the question set is still open. |
| `WebhookVerificationError` | Bad or missing signature (`reason`). |
| `NotificMeError` | Base class; client-side failures such as `timeout` and `network_error`. |

## License

Apache 2.0.
