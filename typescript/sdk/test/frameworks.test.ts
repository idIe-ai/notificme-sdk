import type { AddressInfo } from "node:net";
import express from "express";
import Fastify from "fastify";
import { Hono } from "hono";
import { afterEach, describe, expect, it } from "vitest";
import {
  type NotificMeWebhook,
  expressWebhook,
  expressWebhookOf,
  fastifyWebhook,
  fastifyWebhookOf,
  fastifyWebhookPlugin,
  honoWebhook,
} from "../src/index";
import { vector, vectorHeaders, vectorNow } from "./helpers";

const base = { key: vector.public_key, now: vectorNow };
const tampered = vector.body.replace("Approve", "Deny");

const closers: (() => Promise<void>)[] = [];
afterEach(async () => {
  while (closers.length) await closers.pop()!();
});

async function post(url: string, body: string, headers = vectorHeaders()) {
  const res = await fetch(url, { method: "POST", headers, body });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : undefined };
}

describe("Express", () => {
  async function start(app: express.Express) {
    const server = app.listen(0, "127.0.0.1");
    await new Promise((r) => server.once("listening", r));
    closers.push(() => new Promise((r) => server.close(() => r())));
    return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  }

  function app(withRawParser: boolean) {
    const a = express();
    const mw = [expressWebhook({ ...base })];
    const handler = (req: express.Request, res: express.Response) => {
      const w = expressWebhookOf(req)!;
      res.json({ action: w.reply?.answers.action, topic: w.topic });
    };
    if (withRawParser) a.post("/hook", express.raw({ type: "application/json" }), ...mw, handler);
    else a.post("/hook", ...mw, handler);
    return a;
  }

  it("accepts a valid signature (stream and express.raw)", async () => {
    for (const raw of [false, true]) {
      const url = await start(app(raw));
      expect(await post(`${url}/hook`, vector.body)).toEqual({ status: 200, body: { action: "Approve", topic: "reply" } });
    }
  });

  it("rejects an invalid signature with 401", async () => {
    const url = await start(app(false));
    const r = await post(`${url}/hook`, tampered);
    expect(r.status).toBe(401);
    expect(r.body.error).toMatchObject({ code: "invalid_signature", reason: "invalid_signature" });
    const missing = vectorHeaders();
    delete (missing as Record<string, string>)["notificme-signature"];
    expect((await post(`${url}/hook`, vector.body, missing)).status).toBe(401);
  });

  it("answers 413 for a body over maxBodyBytes before checking the signature", async () => {
    const a = express();
    a.post("/hook", expressWebhook({ ...base, maxBodyBytes: 64 }), (_req, res) => {
      res.sendStatus(204);
    });
    const url = await start(a);
    const r = await fetch(`${url}/hook`, { method: "POST", headers: vectorHeaders(), body: vector.body + " ".repeat(128) });
    expect(r.status).toBe(413);
  });

  it("fails loudly when express.json() consumed the body", async () => {
    const a = express();
    a.post("/hook", express.json(), expressWebhook(base), (_req, res) => {
      res.sendStatus(204);
    });
    const url = await start(a);
    const r = await fetch(`${url}/hook`, { method: "POST", headers: vectorHeaders(), body: vector.body });
    expect(r.status).toBe(500);
  });
});

describe("Fastify", () => {
  it("plugin accepts valid and rejects invalid signatures", async () => {
    const app = Fastify();
    let received: NotificMeWebhook | undefined;
    await app.register(fastifyWebhookPlugin, {
      prefix: "/hooks/notificme",
      ...base,
      handler: (w: NotificMeWebhook) => {
        received = w;
      },
    });
    // The plugin's buffer parser is scoped: other routes keep JSON parsing.
    app.post("/other", async (req) => ({ echo: req.body }));
    await app.ready();
    closers.push(() => app.close());

    const ok = await app.inject({ method: "POST", url: "/hooks/notificme", headers: vectorHeaders(), payload: vector.body });
    expect(ok.statusCode).toBe(204);
    expect(received?.reply?.answers.action).toBe("Approve");

    const bad = await app.inject({ method: "POST", url: "/hooks/notificme", headers: vectorHeaders(), payload: tampered });
    expect(bad.statusCode).toBe(401);
    expect(bad.json().error.code).toBe("invalid_signature");

    const other = await app.inject({ method: "POST", url: "/other", payload: { a: 1 } });
    expect(other.json()).toEqual({ echo: { a: 1 } });
  });

  it("preHandler works with a buffer parser", async () => {
    const app = Fastify();
    app.addContentTypeParser("application/json", { parseAs: "buffer" }, (_req, body, done) => done(null, body));
    app.post("/hook", { preHandler: fastifyWebhook(base) }, async (req) => ({
      action: fastifyWebhookOf(req)?.reply?.answers.action,
    }));
    await app.ready();
    closers.push(() => app.close());

    const ok = await app.inject({ method: "POST", url: "/hook", headers: vectorHeaders(), payload: vector.body });
    expect(ok.json()).toEqual({ action: "Approve" });
    const bad = await app.inject({ method: "POST", url: "/hook", headers: vectorHeaders(), payload: tampered });
    expect(bad.statusCode).toBe(401);
  });
});

describe("Hono", () => {
  it("middleware accepts valid and rejects invalid signatures", async () => {
    const app = new Hono<{ Variables: { notificme: NotificMeWebhook } }>();
    app.post("/hook", honoWebhook(base), async (c) => {
      const w = c.get("notificme");
      // The body is still readable after the middleware.
      const body = await c.req.json();
      return c.json({ action: w.reply?.answers.action, version: body.version });
    });

    const ok = await app.request("/hook", { method: "POST", headers: vectorHeaders(), body: vector.body });
    expect(ok.status).toBe(200);
    expect(await ok.json()).toEqual({ action: "Approve", version: 1 });

    const bad = await app.request("/hook", { method: "POST", headers: vectorHeaders(), body: tampered });
    expect(bad.status).toBe(401);
    expect(((await bad.json()) as { error: { code: string } }).error.code).toBe("invalid_signature");
  });
});
