# Changelog

## 0.1.0

- First release.
- **Ask → Send and Wait for Response:** asks a human and pauses the workflow until the signed answer comes back. The answer is checked against the server's Ed25519 key and the ask must belong to the credential's workspace before the workflow resumes.
- **Ask → Create** and **Ask → Get:** create an ask without waiting, read its status and answer.
- **Event → Send:** send an event with summary, severity, recipients, payload, card and live card ID.
