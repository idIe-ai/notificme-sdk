# notific.me SDKs

Official SDKs and open protocol for [notific.me](https://notific.me): send events, keep live cards up to date, ask humans for decisions and verify the signed webhooks the server sends you.

| Package | Role | Install | Source |
| --- | --- | --- | --- |
| TypeScript / JavaScript | send | `npm install @notificme/sdk` | [`typescript/sdk/`](typescript/sdk/) |
| Python | send | `pip install notificme` | [`python/`](python/) |
| Protocol (JSON Schemas) | | | [`protocol/`](protocol/) |

Every SDK follows the schemas in [`protocol/`](protocol/) and is tested against the same signed webhook vector in [`protocol/test-vectors/`](protocol/test-vectors/).

## Layout

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

## Quick look

```ts
import { NotificMe } from "@notificme/sdk";

const notific = new NotificMe({ token: process.env.NOTIFICME_TOKEN, source: "billing" });
await notific.send({ type: "invoice.failed", severity: "high", summary: "Invoice #42 failed" });
```

```python
from notificme import NotificMe

notific = NotificMe(source="billing")  # reads NOTIFICME_TOKEN
notific.send("invoice.failed", severity="high", summary="Invoice #42 failed")
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

## Releasing

Each SDK is versioned and released on its own, from a tag. GitHub Actions publishes with Trusted Publishing (OIDC), so the repository holds no registry tokens.

| SDK | Bump the version in | Tag | Workflow |
| --- | --- | --- | --- |
| TypeScript | `typescript/sdk/package.json` and `typescript/sdk/src/version.ts` | `ts-v0.2.0` | [`publish-npm.yml`](.github/workflows/publish-npm.yml) |
| Python | `python/src/notificme/_version.py` | `py-v0.2.0` | [`publish-pypi.yml`](.github/workflows/publish-pypi.yml) |

```sh
git tag ts-v0.2.0 && git push origin ts-v0.2.0
```

The workflow runs the full CI, checks that the tag matches the package version, and publishes. Pre-release versions (`0.2.0-beta.1`) go to the `next` dist-tag on npm.

## License

[Apache 2.0](LICENSE).
