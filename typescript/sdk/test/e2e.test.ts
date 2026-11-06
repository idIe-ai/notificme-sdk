// Optional test against a real notific.me server. Runs only when both
// NOTIFICME_SDK_E2E_URL and NOTIFICME_SDK_E2E_TOKEN are set, e.g.:
//
//   NOTIFICME_SDK_E2E_URL=http://localhost:58080 NOTIFICME_SDK_E2E_TOKEN=nm_svc_... npm run test:e2e
//
// The token needs the scopes events:write, asks:write and asks:read.
import { beforeAll, describe, expect, it } from "vitest";
import { AskPendingError, NotificMe, NotificMeApiError } from "../src/index";

const url = process.env.NOTIFICME_SDK_E2E_URL;
const token = process.env.NOTIFICME_SDK_E2E_TOKEN;

describe.skipIf(!url || !token)("e2e against a real server", () => {
  let notific: NotificMe;
  beforeAll(() => {
    notific = new NotificMe({ token, baseUrl: url, source: "sdk-e2e" });
  });

  it("sends an event and replays the same id idempotently", async () => {
    const id = `sdk-e2e-${Date.now()}`;
    const occurredAt = new Date();
    const first = await notific.send({ id, type: "sdk.e2e", severity: "info", summary: "SDK e2e event", occurredAt });
    expect(first.status).toMatch(/queued|duplicate/);
    const again = await notific.send({ id, type: "sdk.e2e", severity: "info", summary: "SDK e2e event", occurredAt });
    expect(again.id).toBe(first.id);
  });

  it("updates a live card", async () => {
    const card = await notific.send({
      type: "sdk.e2e.progress",
      summary: "SDK e2e progress",
      live: true,
      card: { blocks: [{ type: "progress", value: 0.2 }] },
    });
    const updated = await notific.update(card.cardId!, { card: { blocks: [{ type: "progress", value: 1 }] } });
    expect(updated.cardId).toBe(card.cardId);
  });

  it("rejects an invalid event with validation_failed", async () => {
    const err = await notific.send({ type: "Not A Valid Type" }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(NotificMeApiError);
    expect((err as NotificMeApiError).code).toBe("validation_failed");
  });

  it("creates a question set and waits (stops after a few seconds)", async () => {
    const handle = await notific.createAsk({
      title: "SDK e2e question",
      questions: [{ id: "ok", type: "yesno", text: "Is the SDK working?" }],
      timeout: "2m",
    });
    expect(handle.status).toBe("open");
    await expect(handle.wait({ maxWaitMs: 2000 })).rejects.toBeInstanceOf(AskPendingError);
  });

  it("publishes the webhook keys", async () => {
    const res = await fetch(notific.keysUrl);
    expect(res.ok).toBe(true);
    const jwks = (await res.json()) as { keys: { crv: string }[] };
    expect(jwks.keys[0]?.crv).toBe("Ed25519");
  });
});
