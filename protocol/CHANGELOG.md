# Protocol changelog

Every change to the schemas in `protocol/schemas/` is recorded here. While `version` is `"0"` the protocol is unstable and may break compatibility; from v1 on it follows semantic versioning.

## [Unreleased]

### Added

- `question-set.locale` (optional): the asker's language (`en`, `pt-BR` or `es`; `pt`, `pt-PT`, `es-MX`… map to them). `reply.as_text` is written in it. Without it the server uses the `Accept-Language` of `POST /v0/asks`, then `en`. It never follows the language of the person who answers, and it does not change what the human sees. Before, `as_text` was always in Portuguese.
- `question-set.decision` (optional): who may decide and which rule closes the question. `by` (`#group`, `#group>=authority_level`, `@handle` or a member's e-mail; they also receive the card; default: everyone in `to`), `rule` (`any` — the default, the first answer closes it —, `quorum` with `required`, `majority`, `unanimous`, `each_group` — one approval from each entry of `by`, "four eyes"), `veto`, `question` (the vote; default the first question, `yesno` or `single` with `approve_options`) and `requested_by` (the member who asked never approves their own request). Rejections count but do not veto: the question closes as rejected once approval can no longer be reached. One person, one vote. The eligible people are frozen when the question is sent and checked again when they vote. Rules other than `any` need the Team plan or above.
- `question-set.escalate` items may be objects `{ "after": "30m", "to": ["#ceo"] }`: if the question is still open after `after`, the step's recipients also receive it and may decide (the earlier recipients still can). A plain string still works and escalates at half the timeout.
- `to` (and `decision.by`, `escalate[].to`) accept groups: `#all`, `#owners`, `#admins`, `#group` and `#group>=authority_level` (only the people with that level or above in that group).
- `quorum` is now accepted, as a shorthand for `decision { rule: "quorum", required }` among `to`.
- `reply.decision` (how the rule closed it: `rule`, `required`, `eligible`, `approvals`, `rejections`, `outcome` `approved`/`rejected`, `by`, `escalation_step`), `reply.votes[]` (one per person: `identity`, `handle`, `approve`, `answered_at`, `level_used`, `auth_method`, `group`, `authority_level`) and `answered_by.group` / `answered_by.authority_level`. A reply closed as rejected by the rule has `status: "rejected"`.
- Example `examples/contract-board-quorum.question-set.json`.
- `question-set.require_signature` (optional, default `false`): asks the person to sign the answer with their electronic signature (the full name in a cursive style, or drawn by finger or mouse) and initials, on top of the verification `level`. Pro plans and above. Example: `examples/contract-renewal.question-set.json`.
- `reply.e_signature`: the signature the person applied (`name`, `initials`, `method` `typed`/`drawn`, `style`, both images as base64 PNG with their `sha256:` hashes, `consent_version`, `adopted_at`). It is covered by the reply signature like every other field, so the receipt can be rebuilt from the reply alone. Example: `examples/contract-renewal.reply.json`.
- `card` metric block `better` (optional, `"higher"` by default or `"lower"`): which way is good for the number, so renderers color the `delta` by meaning, not by sign (a falling default rate or response time is green).
- `question-set.severity` (optional, default `"high"`): how urgent the decision is, the same values as `event.severity`. With `"critical"` the person's app may take the full screen with an alarm until they decide, if they chose that in the app (Settings → Alerts and sounds).
- `reply.answered_by.auth_method` accepts `"delegated"`: a level 0–1 answer given inside the company's own system (intranet, native app) with a person token issued by the company's backend (`POST /v0/member-tokens`). The company authenticated the person, not notific.me; levels 2–3 from that session are recorded as `"totp"`.

- Example `examples/agent-approval.reply.json` (signed reply to the example question set).
- Example naming convention: `<name>.<schema>.json` (`.event.json`, `.question-set.json`, `.reply.json`, `.card.json`). CI validates each example against the schema named by its suffix (`protocol/validate.py`).
- Semantic rules enforced by the server on top of JSON Schema: question ids are unique; `on_timeout` and `options`/`input` blocks may only reference existing questions; the effective level is the highest of the set's and each question's; level 3+ is never accepted offline.
- Server webhook signature: `Notificme-Signature: t=<unix>,kid=<key_id>,sig=<base64url(Ed25519(t + "." + body))>` header, with the public keys at `/.well-known/notificme-keys.json`.

## v0 — 2026-10-27

- Initial schemas: `event`, `card` (10 blocks), `question-set` and `reply`.
