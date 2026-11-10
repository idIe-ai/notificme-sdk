# Changelog

## Unreleased

- **Ask → group decisions:** new options **Decided By** (who may decide: `#group`, `#group>=authority_level`, `@handle` or e-mail; the others in To only see the ask), **Decision Rule** (Any, Quorum, Majority, Unanimous, Each Group), **Required Approvals** (Quorum), **Veto**, **Requested By** (never approves their own request) and **Approving Choices** (when the vote is a Choice). Rules other than Any need the Team plan or above.
- **Ask → Escalate After:** when Escalate To also receives the ask, if it is still open (`{ after, to }`). Without it, the escalation stays at half of the timeout, as before.
- **To** accepts `#group>=authority_level` (only the people with that level or above in the group).
- Output: with a decision rule, `approved` follows the outcome of the rule (not the last vote), and `decision` and `votes` come with the answer. `status` is `rejected` when the rule closed the ask as rejected.
- Level 4 is described as the device-key signature it is; it does not set how many people approve.
- **Ask → Require Signature** (option): the person signs the answer with their electronic signature (typed in a cursive style or drawn) and initials, on top of the level. The output carries it in `e_signature` (name, method, PNG images and their SHA-256). Pro plans and above.

- **Ask → Summary Language** (option): the language of `as_text` (English by default, Portuguese (Brazil) or Spanish). Before, the summary always came in Portuguese.

## 0.1.0

- First release.
- **Ask → Send and Wait for Response:** asks a human and pauses the workflow until the signed answer comes back. The answer is checked against the server's Ed25519 key and the ask must belong to the credential's workspace before the workflow resumes.
- **Ask → Create** and **Ask → Get:** create an ask without waiting, read its status and answer.
- **Event → Send:** send an event with summary, severity, recipients, payload, card and live card ID.
