<p align="center">
  <a href="https://notific.me">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset=".github/assets/lockup-on-dark.svg">
      <img alt="notific.me" src=".github/assets/lockup.svg" width="300">
    </picture>
  </a>
</p>

<p align="center">
  <a href="https://www.npmjs.com/package/@notificme/sdk"><img alt="npm" src="https://img.shields.io/npm/v/@notificme/sdk?label=npm&color=FF6A00"></a>
  <a href="https://pypi.org/project/notificme/"><img alt="PyPI" src="https://img.shields.io/pypi/v/notificme?label=pypi&color=1E3A8A"></a>
  <a href="https://github.com/idIe-ai/notificme-sdk/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/idIe-ai/notificme-sdk/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-0B1F44"></a>
  <a href="https://docs.notific.me"><img alt="Docs" src="https://img.shields.io/badge/docs-docs.notific.me-FF6A00"></a>
</p>

<br>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/assets/hero-dark.svg">
    <img alt="Your code calls notific.ask(), the person approves on the phone with biometrics, and the signed reply comes back to your code." src=".github/assets/hero-light.svg" width="100%">
  </picture>
</p>

<h3 align="center">Ask a human before your code acts.</h3>

<p align="center">
  Send events with context, ask people for decisions and get the answer back in your code,<br>
  <b>signed on their phone</b>. Like 2FA, but for decisions.
</p>

<p align="center">
  <a href="#quick-start"><b>Quick start</b></a> ·
  <a href="#how-it-works"><b>How it works</b></a> ·
  <a href="typescript/sdk/"><b>TypeScript</b></a> ·
  <a href="python/"><b>Python</b></a> ·
  <a href="protocol/"><b>Protocol</b></a> ·
  <a href="#white-label-for-your-product"><b>White label</b></a> ·
  <a href="https://docs.notific.me"><b>Docs</b></a>
</p>

---

## What is notific.me?

Your services, scripts and AI agents **send events**. notific.me decides who needs to know and delivers them with context (metrics, charts, tables, progress, links). When a decision is needed, it **brings the human answer back to your code**, and for sensitive actions that answer is signed by a key on the person's device, unlocked only by biometrics.

No dashboards to check, no Slack thread to dig through: the person gets what matters, with the options in hand, and your code gets a typed answer with proof of who decided.

<table>
  <tr>
    <td width="50%" valign="top">
      <h4>🙋 Human in the loop, in one call</h4>
      <p><code>ask()</code> creates the question and waits for the answer, or delivers it to your webhook. Yes/no, single and multiple choice, text, number and date.</p>
    </td>
    <td width="50%" valign="top">
      <h4>🤖 Made for AI agents</h4>
      <p>Answers come back typed per question, free text is marked as untrusted, and <code>asText</code> gives the agent a ready-to-read summary. No code? Plug in the <a href="#connect-an-ai-agent-mcp">MCP server</a>.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h4>🔏 Decisions with proof</h4>
      <p>From level 2, answers are signed with Ed25519 by a device key that only biometrics unlock. You get who decided, when, and the signature.</p>
    </td>
    <td width="50%" valign="top">
      <h4>📊 Rich, live cards</h4>
      <p>Text, metrics, charts, tables, lists, images, progress and buttons. Mark a card as <code>live</code> and keep updating it while the work moves.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h4>🪝 Signed webhooks</h4>
      <p>Every webhook carries an Ed25519 signature checked against published keys, with clock tolerance and a stable id for deduplication. One function verifies it.</p>
    </td>
    <td width="50%" valign="top">
      <h4>🪶 Light and portable</h4>
      <p>TypeScript with zero runtime dependencies (Node 18+, Bun, Deno, Workers, browsers). Python 3.10+ with sync and async clients. Retries and timeouts built in.</p>
    </td>
  </tr>
</table>

## What teams use it for

| Who | What reaches the phone | What they do |
| --- | --- | --- |
| 🤖 **AI agents** | "The agent wants to delete 340 duplicated contacts" | Approve, deny, pick an option or answer in text, with biometrics |
| 🛠️ **Dev and infra** | A container died, a deploy finished, a health check failed | Follow a live card with metrics, open the dashboard |
| 💼 **Product and business** | A new lead, a refund to approve, a purchase request | Answer with one tap or fill in a short form |
| 📈 **Management** | A daily summary of a department | Read a digest card instead of opening five dashboards |
| 🤝 **Between companies** | A client approves the agency's deploy, a supplier confirms an order | Decide as an external guest, with the same proof |

## How it works

```mermaid
sequenceDiagram
    autonumber
    participant Code as Your code or AI agent
    participant API as notific.me
    participant Phone as Person's phone
    Code->>API: ask({ to, questions, level: 2 })
    API->>Phone: push in real time, with the card
    Note over Phone: biometrics unlock<br/>the device key
    Phone->>API: answer, signed (Ed25519)
    API-->>Code: typed reply (long-poll) or signed webhook
```

Not every event needs a person right away. Rules in the customer area decide what happens to each one:

```mermaid
flowchart LR
    E["send(event)"] --> D{"dedup and rules"}
    D -->|notify| N["push to the right people"]
    D -->|digest| G["grouped in a digest"]
    D -->|suppress| S["kept in the history only"]
```

### Security levels

Pick how much proof an answer needs. The higher the level, the stronger the guarantee that the right person decided.

| Level | What the person does | Good for |
| :---: | --- | --- |
| **0** | One tap | Acknowledgements, low-risk choices |
| **1** | Answers from a signed-in session | Routine approvals |
| **2** | Biometrics or device passcode in the app (or an authenticator code on the web) | Deploys, refunds, deleting data |
| **3** | Biometrics (or a fresh authenticator code), online, within a short window | Money and production access |
| **4** | Several people approve (quorum) | High-risk changes *(coming soon)* |

## Quick start

1. Create a service token in the customer area at [app.notific.me](https://app.notific.me) with the `events:write`, `asks:write` and `asks:read` scopes.
2. Keep it on your server as `NOTIFICME_TOKEN`.
3. Install the SDK for your language.

### TypeScript

```sh
npm install @notificme/sdk
```

```ts
import { NotificMe } from "@notificme/sdk";

const notific = new NotificMe({ source: "deploy-bot" }); // reads NOTIFICME_TOKEN

// Tell people what is happening.
await notific.send({ type: "deploy.started", summary: "Deploying api v2.14" });

// Ask before acting.
const reply = await notific.ask({
  to: "@ana",
  title: "Deploy api v2.14 to production?",
  questions: [{ id: "ok", type: "yesno", text: "Deploy now?" }],
  level: 2, // biometrics on the phone
  timeout: "30m",
  onTimeout: { ok: false },
});

if (reply.status === "answered" && reply.answers.ok) {
  await deploy(); // reply.answeredBy, reply.signature: proof of who decided
}
```

### Python

```sh
pip install notificme
```

```python
from notificme import NotificMe

notific = NotificMe(source="deploy-bot")  # reads NOTIFICME_TOKEN

notific.send("deploy.started", summary="Deploying api v2.14")

reply = notific.ask(
    "Deploy api v2.14 to production?",
    [{"id": "ok", "type": "yesno", "text": "Deploy now?"}],
    to="@ana",
    level=2,
    timeout="30m",
    on_timeout={"ok": False},
)

if reply.status == "answered" and reply.answers["ok"]:
    deploy()
```

### Getting the answer on a webhook

Long-running jobs don't need to wait: pass a `callbackUrl` and verify what arrives.

```ts
import { receiveWebhook } from "@notificme/sdk";

const webhook = await receiveWebhook(rawBody, headers, notific.keysUrl, { questions });
if (webhook.reply?.answers.ok) await deploy();
```

Full guides, including Express, Fastify and Hono, live in each package: [TypeScript](typescript/sdk/) · [Python](python/).

## Connect an AI agent (MCP)

notific.me also runs a hosted [MCP](https://modelcontextprotocol.io) server, so any MCP-compatible agent can ask a human before acting, with no SDK code. It exposes `request_approval`, `ask_human`, `get_answer` and `notify`, plus workspace tools limited to the token's scopes. Create the token in a service of type *AI agent*: its write permissions start with human approval turned on.

```sh
claude mcp add --transport http notificme https://api.notific.me/v0/mcp   --header "Authorization: Bearer nm_svc_..."
```

For Claude Desktop, Cursor, VS Code and other clients:

```json
{
  "mcpServers": {
    "notificme": {
      "type": "http",
      "url": "https://api.notific.me/v0/mcp",
      "headers": { "Authorization": "Bearer nm_svc_..." }
    }
  }
}
```

Every call an agent makes is audited with its service and token, and writes you mark as "requires human approval" only run after a person approves.

## White label for your product

Want your users to get notifications and decisions **under your own brand**? notific.me offers white label under an enterprise agreement, so you can put a human in the loop for your own customers without building the delivery, signing and evidence infrastructure behind it.

- **Your app, your brand:** a build of the official app with your name, icon, colors and server, using your own push keys.
- **Your infrastructure:** a dedicated instance managed by us, or self-hosted on your servers, with the same SDKs and protocol.
- **Inside your product:** the inbox and decisions shown in your own app with the client SDK, signed in through your single sign-on *(coming soon)*.

Each agreement sets the brand, the deployment model and data retention. [Talk to us](https://notific.me) to set it up.

## Packages

| Package | Role | Install | Source |
| --- | --- | --- | --- |
| TypeScript / JavaScript | send events, ask, verify webhooks | `npm install @notificme/sdk` | [`typescript/sdk/`](typescript/sdk/) |
| Python | send events, ask, verify webhooks | `pip install notificme` | [`python/`](python/) |
| Protocol (JSON Schemas) | the open contract every SDK follows | | [`protocol/`](protocol/) |
| Browser client | inbox and decisions inside your own app | *planned* | [`typescript/client/`](typescript/client/) |
| Kotlin, Swift | native clients | *planned* | [`kotlin/`](kotlin/), [`swift/`](swift/) |

Every SDK follows the schemas in [`protocol/`](protocol/) and is tested against the same signed webhook vector in [`protocol/test-vectors/`](protocol/test-vectors/).

**Open protocol, closed platform.** The protocol, the SDKs, the channels and the plugins are open source under Apache 2.0, so anyone can integrate or build their own interface. The official app and the hosted service are run by notific.me.

## Repository layout

The repository is organized by language. Each language has one toolchain and may hold two packages: one to **send** (server side, with a service token) and one **client** to show the inbox and answer decisions inside your own app (with a short-lived person token).

```
protocol/          JSON Schemas, examples and shared test vectors
typescript/
  sdk/             @notificme/sdk: send events, ask, verify webhooks
  client/          @notificme/client: inbox and decisions in the browser (planned)
python/            notificme: send events, ask, verify webhooks
kotlin/            Android client (planned)
swift/             iOS client (planned)
```

## Development

```sh
# TypeScript
cd typescript/sdk && npm ci && npm test

# Python
cd python && python -m venv .venv && .venv/bin/pip install -e ".[dev]" && .venv/bin/pytest

# Protocol
pip install jsonschema referencing && python protocol/validate.py
```

## Contributing

This repository is published from the notific.me monorepo, where the SDKs are developed together with the server. Issues and pull requests are welcome here: a maintainer applies accepted changes upstream, and they come back in the next sync.

<details>
<summary><b>Releasing</b> (maintainers)</summary>

<br>

Each SDK is versioned and released on its own, from a tag. GitHub Actions publishes with Trusted Publishing (OIDC), so the repository holds no registry tokens.

| SDK | Bump the version in | Tag | Workflow |
| --- | --- | --- | --- |
| TypeScript | `typescript/sdk/package.json` and `typescript/sdk/src/version.ts` | `ts-v0.2.0` | [`publish-npm.yml`](.github/workflows/publish-npm.yml) |
| Python | `python/src/notificme/_version.py` | `py-v0.2.0` | [`publish-pypi.yml`](.github/workflows/publish-pypi.yml) |

Releases are automatic: when a version bump is synced here from the monorepo, the sync creates the missing tag on the synced commit, and the tag starts the publish workflow. A tag can also be pushed by hand:

```sh
git tag ts-v0.2.0 && git push origin ts-v0.2.0
```

The workflow runs the full CI, checks that the tag matches the package version, and publishes. Pre-release versions (`0.2.0-beta.1`) go to the `next` dist-tag on npm.

</details>

## License

[Apache 2.0](LICENSE).
