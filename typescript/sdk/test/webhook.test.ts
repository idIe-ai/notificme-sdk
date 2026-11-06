import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  NotificMe,
  UntrustedText,
  WebhookVerificationError,
  clearWebhookKeyCache,
  parseSignatureHeader,
  receiveWebhook,
  receiveWebhookRequest,
  verifyWebhook,
} from "../src/index";
import { fakeServer, sendJson, vector, vectorHeaders, vectorNow } from "./helpers";

const opts = { now: vectorNow };

async function reason(p: Promise<unknown>): Promise<string> {
  const err = await p.then(
    () => undefined,
    (e: unknown) => e,
  );
  expect(err).toBeInstanceOf(WebhookVerificationError);
  return (err as WebhookVerificationError).reason;
}

beforeEach(() => clearWebhookKeyCache());

describe("verifyWebhook() with the Go signer vector", () => {
  it("accepts the base64url public key", async () => {
    const v = await verifyWebhook(vector.body, vector.header, vector.public_key, opts);
    expect(v).toEqual({ timestamp: vector.timestamp, keyId: vector.key_id });
  });

  it("accepts raw key bytes, a JWK and a JWKS", async () => {
    const raw = new Uint8Array(Buffer.from(vector.public_key, "base64url"));
    await verifyWebhook(vector.body, vector.header, raw, opts);
    await verifyWebhook(vector.body, vector.header, vector.jwks.keys[0]!, opts);
    await verifyWebhook(vector.body, vector.header, vector.jwks, opts);
  });

  it("accepts the body as Buffer / ArrayBuffer", async () => {
    const buf = Buffer.from(vector.body, "utf8");
    await verifyWebhook(buf, vector.header, vector.public_key, opts);
    const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
    await verifyWebhook(ab, vector.header, vector.public_key, opts);
  });

  it("rejects a tampered body", async () => {
    const tampered = vector.body.replace("Approve", "Deny");
    expect(await reason(verifyWebhook(tampered, vector.header, vector.public_key, opts))).toBe("invalid_signature");
  });

  it("rejects a re-serialized body", async () => {
    const reserialized = JSON.stringify(JSON.parse(vector.body), null, 2);
    expect(await reason(verifyWebhook(reserialized, vector.header, vector.public_key, opts))).toBe("invalid_signature");
  });

  it("rejects a forged signature and another key", async () => {
    const forged = vector.header.replace(/sig=.*/, "sig=" + Buffer.alloc(64, 1).toString("base64url"));
    expect(await reason(verifyWebhook(vector.body, forged, vector.public_key, opts))).toBe("invalid_signature");
    const otherKey = Buffer.alloc(32, 7).toString("base64url");
    expect(await reason(verifyWebhook(vector.body, vector.header, otherKey, opts))).toMatch(/invalid_signature|invalid_key/);
  });

  it("rejects a changed timestamp (it is signed)", async () => {
    const shifted = vector.header.replace(`t=${vector.timestamp}`, `t=${vector.timestamp + 1}`);
    expect(await reason(verifyWebhook(vector.body, shifted, vector.public_key, opts))).toBe("invalid_signature");
  });

  it("enforces the tolerance window", async () => {
    const late = new Date((vector.timestamp + 301) * 1000);
    expect(await reason(verifyWebhook(vector.body, vector.header, vector.public_key, { now: late }))).toBe(
      "timestamp_out_of_tolerance",
    );
    const early = new Date((vector.timestamp - 301) * 1000);
    expect(await reason(verifyWebhook(vector.body, vector.header, vector.public_key, { now: early }))).toBe(
      "timestamp_out_of_tolerance",
    );
    await verifyWebhook(vector.body, vector.header, vector.public_key, { now: late, toleranceSeconds: 0 });
    await verifyWebhook(vector.body, vector.header, vector.public_key, { now: late, toleranceSeconds: 600 });
  });

  it("rejects missing or malformed headers", async () => {
    expect(await reason(verifyWebhook(vector.body, undefined, vector.public_key, opts))).toBe("missing_header");
    expect(await reason(verifyWebhook(vector.body, "", vector.public_key, opts))).toBe("missing_header");
    expect(await reason(verifyWebhook(vector.body, "garbage", vector.public_key, opts))).toBe("malformed_header");
    expect(await reason(verifyWebhook(vector.body, "t=abc,sig=xyz", vector.public_key, opts))).toBe("malformed_header");
    expect(await reason(verifyWebhook(vector.body, `t=${vector.timestamp},sig=!!`, vector.public_key, opts))).toBe(
      "invalid_signature",
    );
  });

  it("rejects an unknown kid in a JWKS", async () => {
    const header = vector.header.replace(`kid=${vector.key_id}`, "kid=other");
    expect(await reason(verifyWebhook(vector.body, header, vector.jwks, opts))).toBe("unknown_key");
  });

  it("parses the header like the Go signer", () => {
    expect(parseSignatureHeader(" t=1 , kid=k ,sig=a=b ,x=y")).toEqual({ t: "1", kid: "k", sig: "a=b" });
    expect(parseSignatureHeader("t=1")).toBeUndefined();
  });
});

describe("keys URL", () => {
  let server: Awaited<ReturnType<typeof fakeServer>> | undefined;
  afterEach(async () => {
    await server?.close();
    server = undefined;
  });

  it("downloads and caches the JWKS", async () => {
    server = await fakeServer((_req, res) => sendJson(res, 200, vector.jwks));
    const url = `${server.url}/.well-known/notificme-keys.json`;
    await verifyWebhook(vector.body, vector.header, url, opts);
    await verifyWebhook(vector.body, vector.header, { keysUrl: url }, opts);
    expect(server.requests).toHaveLength(1);
    expect(server.requests[0]!.url).toBe("/.well-known/notificme-keys.json");
  });

  it("is used by NotificMe#receiveWebhook", async () => {
    server = await fakeServer((_req, res) => sendJson(res, 200, vector.jwks));
    const n = new NotificMe({ token: "t", baseUrl: server.url });
    const w = await n.receiveWebhook(vector.body, vectorHeaders(), opts);
    expect(w.topic).toBe("reply");
    await expect(n.verifyWebhook(vector.body + " ", vector.header, opts)).rejects.toBeInstanceOf(WebhookVerificationError);
  });

  it("reports an unreachable keys URL as unknown_key", async () => {
    server = await fakeServer((_req, res) => sendJson(res, 500, {}));
    expect(await reason(verifyWebhook(vector.body, vector.header, `${server.url}/keys`, opts))).toBe("unknown_key");
  });
});

describe("receiveWebhook()", () => {
  const questions = [
    { id: "action", type: "single", text: "What should we do?", options: ["Approve", "Deny"] },
    { id: "note", type: "text", text: "Notes", optional: true },
  ] as const;

  it("returns headers, payload and a typed reply", async () => {
    const w = await receiveWebhook(vector.body, vectorHeaders(), vector.public_key, { ...opts, questions });
    expect(w).toMatchObject({
      id: "019a2b3c-aaaa-7000-8000-000000000001",
      topic: "reply",
      attempt: 1,
      timestamp: vector.timestamp,
      keyId: vector.key_id,
      rawBody: vector.body,
    });
    expect(w.reply?.status).toBe("answered");
    expect(w.reply?.answers.action).toBe("Approve");
    const note = w.reply?.answers.note;
    expect(note).toBeInstanceOf(UntrustedText);
    expect(note?.text).toBe("Only after 10 pm ✓");
  });

  it("works with a fetch Request", async () => {
    const req = new Request("https://example.com/hook", {
      method: "POST",
      headers: vectorHeaders(),
      body: vector.body,
    });
    const w = await receiveWebhookRequest(req, vector.public_key, opts);
    expect(w.reply?.answers.action).toBe("Approve");
  });
});
