# Protocol changelog

Every change to the schemas in `protocol/schemas/` is recorded here. While `version` is `"0"` the protocol is unstable and may break compatibility; from v1 on it follows semantic versioning.

## [Unreleased]

### Added

- Example `examples/agent-approval.reply.json` (signed reply to the example question set).
- Example naming convention: `<name>.<schema>.json` (`.event.json`, `.question-set.json`, `.reply.json`, `.card.json`). CI validates each example against the schema named by its suffix (`protocol/validate.py`).
- Semantic rules enforced by the server on top of JSON Schema: question ids are unique; `on_timeout` and `options`/`input` blocks may only reference existing questions; the effective level is the highest of the set's and each question's; level 3+ is never accepted offline.
- Server webhook signature: `Notificme-Signature: t=<unix>,kid=<key_id>,sig=<base64url(Ed25519(t + "." + body))>` header, with the public keys at `/.well-known/notificme-keys.json`.

## v0 — 2026-10-27

- Initial schemas: `event`, `card` (10 blocks), `question-set` and `reply`.
