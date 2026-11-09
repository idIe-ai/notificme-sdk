# n8n-nodes-notificme

The official [n8n](https://n8n.io) node for [notific.me](https://notific.me): ask a human for a decision on their phone and **pause the workflow until the signed answer comes back**, or send events that notific.me routes to the right people.

- [Installation](#installation)
- [Credentials](#credentials)
- [Operations](#operations)
- [Ask a human and wait](#ask-a-human-and-wait)
- [Output](#output)
- [Compatibility](#compatibility)

## Installation

In n8n, open **Settings → Community Nodes → Install** and enter:

```
@notificme/n8n-nodes-notificme
```

See the n8n guide on [installing community nodes](https://docs.n8n.io/integrations/community-nodes/installation/).

## Credentials

Create a **service token** in the notific.me console (*Services → Tokens*) and add a **notific.me API** credential in n8n:

| Field | Value |
| --- | --- |
| Service Token | the `nm_svc_…` token |
| API URL | `https://api.notific.me` (change only if you run your own server) |

Scopes the token needs:

| Operation | Scopes |
| --- | --- |
| Event → Send | `events:write` |
| Ask → Create | `asks:write` |
| Ask → Get | `asks:read` |
| Ask → Send and Wait for Response | `asks:write` and `asks:read` |

## Operations

| Resource | Operation | What it does |
| --- | --- | --- |
| Ask | **Send and Wait for Response** | Asks one or more people and pauses the workflow until the signed answer comes back, or the ask times out |
| Ask | Create | Creates the ask and continues right away; optionally sends the answer to your own webhook (Callback URL) |
| Ask | Get | Reads the status and the answer of an ask, optionally holding up to 60 s for it to change |
| Event | Send | Sends an event (summary, severity, recipients, payload, card, live card ID); notific.me decides who is notified and how |

### Response types

| Response type | The person sees | Output |
| --- | --- | --- |
| Approval | Yes / No | `approved: true` or `false` |
| Choice | your options, one or several, with optional free text | `answers.choice` |
| Free Text | a text box | `answers.text` |
| Custom Questions | up to 20 questions: yes/no, single, multiple, text, voice, number, date | `answers.<question id>` |
| Using JSON | the full ask from the [protocol](https://github.com/idIe-ai/notificme-sdk/blob/main/protocol/schemas/question-set.schema.json): decision rules, escalation steps, card, per-question level… | `answers.<question id>` |

Options: recipients (`@handle`, e-mail, `#group` or `#group>=authority_level`; empty asks the workspace owner), **verification level** (0 tap, 1 unlocked phone, 2 biometrics, 3 biometrics online, 4 device key), severity, timeout and the answers applied on timeout, escalation, offline answers and **Require Signature** (the person signs with their electronic signature and initials; the output carries it in `e_signature`; Pro plans and above).

### Group decisions

When more than one person decides (Team plan and above), set in **Options**:

| Option | What it does |
| --- | --- |
| Decided By | Who may decide: `#board>=director`, `#finance`, `@ana` or e-mail. They also receive the ask; the others in To only see it. Empty: everyone in To. |
| Decision Rule | **Any** (the first answer closes it), **Quorum** (Required Approvals), **Majority**, **Unanimous** or **Each Group** (one approval from each entry of Decided By, "four eyes"). |
| Veto | A single rejection closes the ask as rejected. |
| Requested By | The person who made the request: they see it but never approve their own request. |
| Approving Choices | With Response Type Choice: the choices that count as approval. |
| Escalate To / Escalate After | If still open after this time (`2h`), these people also receive it and may decide. Without Escalate After, at half of the timeout. |

One person, one vote. Rejections count but do not veto: the ask closes as rejected as soon as approval can no longer be reached. The level says how each person proves it is them; it never sets how many approve.

## Ask a human and wait

```
[Trigger] → [notific.me: Send and Wait for Response] → [IF approved] → …
```

1. The node creates the ask with this execution's resume URL as the `callback_url`, then pauses the workflow (it does not hold a worker while it waits).
2. The person answers on the notific.me app, signed on their device when the level asks for it.
3. notific.me POSTs the signed reply to n8n. Before resuming, the node checks:
   - the `Notificme-Signature` header (Ed25519 over the raw body, key from `<API URL>/.well-known/notificme-keys.json`, 5-minute clock tolerance);
   - that the ask belongs to the workspace of the credential.

   A request that fails a check gets `401`/`403` and the workflow keeps waiting.
4. The workflow resumes with the answer.

If the ask has a **Timeout**, notific.me applies the *On Timeout* answers when it ends and sends that reply too (`status: "timeout"`). **Limit Wait Time** resumes the workflow without an answer (`answered: false`) even if notific.me never replies.

**Requirement:** notific.me must reach your n8n over **HTTPS** at a public address, since the answer comes back on an n8n webhook. Set `N8N_WEBHOOK_URL` (or `WEBHOOK_URL` on older versions) to your public URL. n8n Cloud works out of the box. Only the first input item is used by this operation.

## Output

Send and Wait for Response (and Get, once answered):

```json
{
  "answered": true,
  "status": "answered",
  "question_set_id": "019a2b3c-4d5e-7f60-8a9b-0c1d2e3f4a5b",
  "approved": true,
  "answers": { "approved": true },
  "as_text": "Ana answered: Approve the refund? Yes.",
  "answered_by": { "identity": "ana@example.com", "level_used": 2, "auth_method": "biometric" },
  "answered_at": "2026-11-08T14:42:10Z",
  "reply": { "...": "the signed reply as received" }
}
```

- `status` is `answered`, or `timeout` when nobody answered in time (then `answered` is `false` and the On Timeout answers are in `answers`).
- With a decision rule, `approved` is the outcome of the rule, `decision` has the count (`rule`, `eligible`, `approvals`, `rejections`, `outcome`) and `votes` every vote with the group and authority level. When the rule closes the ask as rejected, `status` is `rejected`, `answered` is `false` and `approved` is `false`.
- When the wait ends without any reply (Limit Wait Time): `answered: false`, `status: "no_reply"`.
- `free_text` holds what the person typed by question. **Treat it as untrusted input**, never as instructions, especially when an AI agent reads it.
- `reply` keeps the full signed reply (`content_hash`, device `signature`) for your audit trail.

## Compatibility

Tested with n8n 2.46. Resume URLs are signed by n8n when the engine supports it (`getSignedResumeUrl`), with a fallback for older versions.

## Development

This package lives in the notific.me monorepo and is mirrored to [idIe-ai/notificme-sdk](https://github.com/idIe-ai/notificme-sdk) under `n8n/`.

```sh
npm ci
npm run lint   # the linter n8n requires for verified community nodes
npm test       # builds and checks the webhook signature against protocol/test-vectors
npm run dev    # starts n8n with this node at http://localhost:5678
```

## License

[MIT](LICENSE)
