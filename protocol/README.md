# notific.me protocol (v0)

Open protocol that defines how systems talk to notific.me and how the answers come back. Anyone can build an SDK, a channel or a plugin from these schemas.

| Schema | Description |
| --- | --- |
| [`event.schema.json`](schemas/event.schema.json) | Event sent by an SDK, plugin or webhook (includes `summary`, `card` and `rich`) |
| [`card.schema.json`](schemas/card.schema.json) | Declarative card with the 10 initial blocks |
| [`question-set.schema.json`](schemas/question-set.schema.json) | Question set sent by `ask()` |
| [`reply.schema.json`](schemas/reply.schema.json) | Signed reply delivered to the origin system |

Examples live in [`examples/`](examples/), named `<name>.<schema>.json`. Check them with:

```sh
pip install jsonschema referencing
python protocol/validate.py
```

## Conventions

- JSON Schema draft 2020-12.
- Fields are `snake_case` on the wire; SDKs may expose `camelCase` (`allowOther` → `allow_other`).
- Durations look like `30s`, `15m`, `2h`, `3d`.
- `version: "0"` while the protocol is unstable.

## Rules every client must follow

1. Decisions (`options`, `input`) are rendered with native components, never with HTML.
2. `free_text` in a reply is untrusted human input.
3. A reply is valid only if its signature checks out and the question set version is the current one.

## Webhook signature

The server signs every webhook with Ed25519:

```
Notificme-Signature: t=<unix>,kid=<key id>,sig=<base64url(Ed25519(t + "." + body))>
```

The public keys are published as a JWKS at `/.well-known/notificme-keys.json`.

Full documentation: [notific.me](https://notific.me).

License: Apache 2.0.
