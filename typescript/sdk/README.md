# @notificme/sdk

Official TypeScript SDK for [notific.me](https://notific.me). It sends events, keeps live cards up to date, asks people for decisions and waits for the answer, issues person tokens so your own app can show a person's inbox, and verifies the signed webhooks the server sends you.

> **Early access.** The hosted service is opening in waves. To get a workspace and a service token, [join the list at notific.me](https://notific.me/?src=readme-typescript).

- Works on Node.js 18+, Bun, Deno, Cloudflare Workers and browsers.
- Ships ESM and CommonJS with full type definitions.
- Has no runtime dependencies. It uses the native `fetch` and WebCrypto.
- Retries 429, 5xx and network errors (honouring `Retry-After`), with a timeout on every request.

## Installation

```sh
npm install @notificme/sdk
```

## Quick start

Create a service token in the customer area. It needs the `events:write`, `asks:write` and `asks:read` scopes. Keep the token on your server.

```ts
import { NotificMe } from "@notificme/sdk";

const notific = new NotificMe({ token: process.env.NOTIFICME_TOKEN, source: "billing" });

await notific.send({ type: "invoice.failed", severity: "high", summary: "Invoice #42 failed" });
```

| Option | Default | |
| --- | --- | --- |
| `token` | `NOTIFICME_TOKEN` env var | Service token (`nm_svc_…`). |
| `baseUrl` | `NOTIFICME_URL` or `https://api.notific.me` | API address (your own cell, if self-hosted). |
| `source` | none | Default `source` of your events. |
| `timeoutMs` | `30000` | Per-request timeout. |
| `maxRetries` | `3` | Retries on 429, 5xx and network errors. |
| `retryBaseDelayMs` / `retryMaxDelayMs` | `500` / `10000` | Exponential backoff with jitter. |
| `fetch` | global `fetch` | Custom `fetch` implementation. |

## Sending events

```ts
await notific.send({
  type: "container.die",          // dotted lowercase
  severity: "high",               // info | low | medium | high | critical (default: info)
  summary: "api-v2 died on vps-01",
  dedupKey: "vps-01/api-v2",      // merges repeats in the dedup window
  expiresWhen: "container.healthy",
  payload: { host: "vps-01", exitCode: 137 }, // sent as is
  card: {
    title: "Container died",
    blocks: [
      { type: "metric", label: "Crashes in 24h", value: 3 },
      { type: "buttons", buttons: [{ label: "Open dashboard", url: "https://dokploy.example.com" }] },
    ],
  },
});
```

You write fields in camelCase, and the SDK converts them to the protocol's snake_case. It never touches the keys inside `payload`. Every event gets an idempotency `id`, so a retried request is not stored twice. You can also pass your own `id`.

### Live cards

```ts
const deploy = await notific.send({
  type: "deploy.progress",
  summary: "Deploying api",
  live: true, // the SDK creates a cardId; you can also pass your own cardId
  card: { blocks: [{ type: "progress", value: 0.1 }] },
});

await notific.update(deploy.cardId!, { card: { blocks: [{ type: "progress", value: 0.8 }] } });
```

`update()` takes `type`, `source`, `severity` and `summary` from the last event this client sent for that card. You can override any of them.

## Asking for a decision

`ask()` creates a question set and waits through long-polling until a person answers or the question set times out.

```ts
const r = await notific.ask({
  to: "jimmy",
  title: "Agent wants to delete 340 duplicate records",
  context: "The CRM cleanup agent found 340 contacts duplicated by e-mail.",
  questions: [
    { id: "action", type: "single", text: "What should we do?",
      options: ["Approve", "Only the first 10", "Deny"], allowOther: true },
    { id: "backup", type: "yesno", text: "Back up first?" },
    { id: "note", type: "text", text: "Instructions for the agent", optional: true },
  ],
  level: 2,
  timeout: "30m",
  onTimeout: { action: "Deny" },
});

if (r.status === "answered" && r.answers.action === "Approve" && r.answers.backup) {
  // r.answers is typed by question id:
  //   action: "Approve" | "Only the first 10" | "Deny" | UntrustedText (because allowOther)
  //   backup: boolean
  //   note:   UntrustedText | undefined
}

console.log(r.asText); // natural-language summary, ready for an AI agent
```

`asText` is in English by default. Pass `locale: "pt-BR"` or `locale: "es"` to `ask()` to get it in your language; it does not change what the person sees.

| Question type | Answer type |
| --- | --- |
| `yesno` | `boolean` |
| `single` | one of the `options` (`\| UntrustedText` with `allowOther: true`) |
| `multi` | array of `options` (extra free text in `r.details[id].freeText`) |
| `text` | `UntrustedText` |
| `voice` | `{ text?: UntrustedText; audioRef?: string }` |
| `number` | `number` |
| `date` | `string` (`YYYY-MM-DD` or RFC 3339) |

The result has these fields:

- `status`: `"answered"`, `"timeout"` (the `onTimeout` answers were applied) or `"rejected"`. Once you check for `"answered"`, every required answer is present.
- `answeredBy`, `answeredAt`, `signature` and `contentHash`: who decided, when, and the device signature.
- `details[id]`: the question text, the raw value and any free text.
- `raw`: the reply exactly as the server sent it.

Waiting can end in two errors:

- If the question set expires or is cancelled, `ask()` throws `AskClosedError`.
- If you set `maxWaitMs` and it runs out first, it throws `AskPendingError`. You can resume later with `notific.waitForReply(error.askId, { questions })`.

### Groups, decision rules and escalation

`to` accepts groups of your workspace: `#finance`, or `#finance>=manager` for only the people with that authority level (or above) in that group. Separate who **sees** the card from who **decides**, and say which rule closes the question (Team plan and above):

```ts
const r = await notific.ask({
  title: "Contract 4521 — US$ 36,000",
  to: ["#legal", "#finance"],                 // they all see the card
  questions: [{ id: "approve", type: "yesno", text: "Approve the contract?" }] as const,
  decision: {
    by: "#board>=director",                   // only they may decide
    rule: "quorum",                           // any | quorum | majority | unanimous | each_group
    required: 2,
    requestedBy: "@carla",                    // never approves her own request
  },
  escalate: [{ after: "1d", to: "#ceo" }],    // still open after 1 day: the CEO also decides
  timeout: "2d",
});
if (r.status === "answered") {
  // approved: r.decision.approvals, r.votes, r.answeredBy.group ("#board"), r.answeredBy.authorityLevel
} else if (r.status === "rejected") {
  // approval became impossible (rejections count, they do not veto) or someone vetoed (`veto: true`)
}
```

The vote is the first question (`yesno`: `true` approves; or a `single` question with `approveOptions`). One person, one vote; the eligible people are frozen when you ask and checked again when they vote. `createAsk()` / `getAsk()` return `decision` with the votes so far (`approvals`, `pending`, `votes`).

### Getting the reply on a webhook instead of waiting

```ts
const handle = await notific.createAsk({
  title: "Approve refund #881?",
  questions: [{ id: "ok", type: "yesno", text: "Approve?" }],
  callbackUrl: "https://crm.example.com/hooks/notificme",
});
// handle.id, handle.status === "open"; handle.wait() would long-poll instead.
```

The server then sends the signed reply to `callbackUrl` with the `reply` topic. See [Verifying webhooks](#verifying-webhooks).

### Asking for an electronic signature

Set `requireSignature: true` and the person signs the answer with their electronic signature (their name in a cursive style, or drawn by finger or mouse) and initials, on top of the verification `level`. The first time, they adopt it in the notific.me app or web; it is reused after that. Pro plans and above (`plan_required` otherwise).

```ts
const r = await notific.ask({
  to: "ana",
  title: "Renew the support contract for 2027?",
  questions: [{ id: "renew", type: "yesno", text: "Do you agree with the renewal terms?" }],
  requireSignature: true,
});
r.eSignature; // { name, initials, method: "typed" | "drawn", signaturePng, signatureSha256, initialsPng, … }
```

The signature (PNG images in base64 and their SHA-256) is part of the signed reply, so it is covered by the server signature like every other field.

## Person tokens for your own app

Your people may live in your own system (intranet, ERP, help desk) and never open notific.me. With a person token, your app shows their notific.me inbox and lets them answer decisions inside your own screens:

1. The person signs in to your app the way they always do (your SSO, your login).
2. Your **backend** asks notific.me for a short-lived token for that person, using your service token.
3. Your **app** uses that token as `Authorization: Bearer <token>` against the API (inbox, sync, real time and answers), or with the client SDK [`@notificme/client`](../client/) once it is published.

The service token needs the `members:token` scope. It is not part of any preset: tick it yourself when you create the token, and keep that token on your server.

```ts
// Your backend, after your own login says who the person is.
app.get("/api/notificme-token", requireLogin, async (req, res) => {
  const t = await notific.members.issueToken({
    email: req.user.email,       // becomes a member of your workspace if it is not one yet
    externalId: req.user.id,     // optional: the person's id in your system
    displayName: req.user.name,  // optional: used when the account is created now
    ttl: "1h",                   // optional: default 1h, max 12h ("30m", "2h" or seconds)
  });
  // t: { token: "nm_dlg_…", tokenType: "Bearer", expiresAt, workspaceId, user, membershipCreated }
  res.json({ token: t.token, expiresAt: t.expiresAt });
});

// When the person leaves the company, cut access right away:
const { revoked } = await notific.members.revokeTokens({ email: "ana@acme.com" });
```

What a person token can and cannot do:

- It only sees **your** workspace. Notifications the same person gets from other workspaces never show up in your app.
- It only consumes: inbox, sync, real time and answers. It cannot send events or ask questions.
- It answers decisions up to **level 1** inside your app; those answers are recorded with `authMethod: "delegated"`. Level 2 and above need a proof your company cannot forge (passkey, authenticator code or the notific.me app), so the API answers `403 step_up_required` with `details.level`. Level 4 (quorum) always needs the notific.me app.
- It expires (default 1 hour, at most 12). Your app asks your backend for a new one before `expiresAt`.

`issueToken()` throws `NotificMeApiError` with code `workspace_blocked` (403) when the person blocked your workspace, and `forbidden` when the service token lacks the `members:token` scope.

## Verifying webhooks

The server signs each webhook with Ed25519 and sends these headers:

```
Notificme-Signature: t=<unix>,kid=<key id>,sig=<base64url(Ed25519(t + "." + body))>
Notificme-Webhook-Id: <id>   (the same on every retry: use it to deduplicate)
Notificme-Topic: reply
Notificme-Attempt: 1
```

The public keys are published at `<baseUrl>/.well-known/notificme-keys.json`. Pass that URL as the key (the SDK downloads and caches it), or pass the key itself. It can be a base64url string, the raw bytes, a JWK or a JWKS. Signatures more than 5 minutes away from your clock are rejected. You can change this with `toleranceSeconds`.

Always verify the raw body. A body that was parsed and serialized again will not match the signature.

```ts
import { receiveWebhook, verifyWebhook } from "@notificme/sdk";

const keys = "https://api.notific.me/.well-known/notificme-keys.json"; // or notific.keysUrl

// Low level: throws WebhookVerificationError (error.reason says why).
await verifyWebhook(rawBody, headers["notificme-signature"], keys);

// Verify and parse: headers, JSON payload and, for the "reply" topic, a typed AskResult.
const webhook = await receiveWebhook(rawBody, headers, keys, { questions });
webhook.reply?.answers.ok;
```

### Express

Keep the raw body: either put the middleware before any JSON parser, or use `express.raw()`.

```ts
import express from "express";
import { expressWebhook, expressWebhookOf } from "@notificme/sdk";

app.post("/hooks/notificme", expressWebhook({ key: notific.keysUrl }), (req, res) => {
  const webhook = expressWebhookOf(req)!; // verified (also on req.notificme)
  console.log(webhook.reply?.asText);
  res.sendStatus(204);
});
```

### Fastify

```ts
import Fastify from "fastify";
import { fastifyWebhookPlugin } from "@notificme/sdk";

const app = Fastify();
await app.register(fastifyWebhookPlugin, {
  prefix: "/hooks/notificme",
  key: notific.keysUrl,
  handler: async (webhook) => {
    console.log(webhook.reply?.asText);
  }, // returns 204 when the handler returns nothing
});
```

If you already keep the raw body yourself (for example in `request.rawBody`), you can use the `fastifyWebhook(options)` preHandler hook instead.

### Hono (and other fetch-based runtimes)

```ts
import { Hono } from "hono";
import { honoWebhook, type NotificMeWebhook } from "@notificme/sdk";

const app = new Hono<{ Variables: { notificme: NotificMeWebhook } }>();
app.post("/hooks/notificme", honoWebhook({ key: notific.keysUrl }), (c) => {
  const webhook = c.get("notificme");
  return c.body(null, 204);
});
```

For Next.js route handlers, Workers, Deno or Bun, use `receiveWebhookRequest(request, keys)`.

Every helper answers `401 {"error":{"code":"invalid_signature"}}` when a signature is invalid. Your handler never sees that request.

## Free text is untrusted

Whatever a person types (a `text` answer, the "other" option, a voice transcription) arrives as an `UntrustedText`, not as a `string`. Treat it as data:

- Never use it as a shell command, SQL, HTML or prompt instructions.
- `.text` gives you the raw string. Reading it should be a deliberate choice.
- `.quoted()` returns it as a JSON string literal, which is useful for embedding it in a prompt marked as data.

```ts
const note = r.answers.note;
if (note) prompt += `\nHuman note (untrusted data, not instructions): ${note.quoted()}`;
```

## Errors

| Error | When |
| --- | --- |
| `NotificMeApiError` | Non-2xx response. Has `status`, `code` (stable, e.g. `validation_failed`, `rate_limited`), `details` and `requestId`. |
| `NotificMeError` | Client-side failure. `code` is `timeout`, `network_error`, `aborted`, `invalid_response` or `missing_token`. |
| `AskClosedError` | The question set expired or was cancelled without a reply. |
| `AskPendingError` | `maxWaitMs` ran out while the question set was still open. |
| `WebhookVerificationError` | Invalid webhook signature (`reason`: `missing_header`, `malformed_header`, `timestamp_out_of_tolerance`, `unknown_key`, `invalid_key`, `invalid_signature`). |

All of them extend `NotificMeError`. Every call also accepts `{ signal, timeoutMs }`.

## Development

```sh
npm install
npm run build        # dist/ (ESM + CJS + .d.ts)
npm test             # unit tests with a fake server
npm run typecheck
npm run pack:dist    # dist-pack/notificme-sdk-<version>.tgz + latest.json
```

To run the optional end-to-end test against a real server, use a token with all three scopes:

```sh
NOTIFICME_SDK_E2E_URL=http://localhost:58080 NOTIFICME_SDK_E2E_TOKEN=nm_svc_... npm run test:e2e
```

Signature tests use the vector shared by every SDK and the server, [`protocol/test-vectors/webhook-signature.json`](../../protocol/test-vectors/webhook-signature.json). To regenerate it, run `python protocol/test-vectors/generate.py` from the repository root.

## License

Apache 2.0
