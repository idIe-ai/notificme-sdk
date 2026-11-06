/**
 * Webhook helpers for Express, Fastify and Hono. They have no dependency on
 * the frameworks: the types below are the minimum each helper uses.
 *
 * Every helper verifies the signature over the raw body and answers
 * `401 {"error":{"code":"invalid_signature"}}` when it does not match.
 */

import { WebhookVerificationError } from "./errors";
import type { Question } from "./types";
import { utf8 } from "./util";
import {
  type HeadersLike,
  type NotificMeWebhook,
  type ReceiveWebhookOptions,
  type WebhookKey,
  receiveWebhook,
} from "./webhook";

export interface WebhookHelperOptions<Qs extends readonly Question[] = readonly Question[]>
  extends ReceiveWebhookOptions<Qs> {
  /**
   * Server public key, JWKS or keys URL. Usually
   * `"https://api.notific.me/.well-known/notificme-keys.json"` or `notific.keysUrl`.
   */
  key: WebhookKey;
  /** Called when the signature is invalid (for logging). The helper still answers 401. */
  onInvalid?: (error: WebhookVerificationError) => void;
  /**
   * Maximum body size read from the request stream when no raw body parser ran
   * (default 1 MiB). Larger requests get `413` before the signature is checked.
   */
  maxBodyBytes?: number;
}

function rejectionBody(err: WebhookVerificationError) {
  return { error: { code: "invalid_signature", reason: err.reason, message: err.message } };
}

type RawInput = string | Uint8Array | ArrayBuffer;

function asRaw(body: unknown): RawInput | undefined {
  if (typeof body === "string" || body instanceof Uint8Array || body instanceof ArrayBuffer) return body;
  return undefined;
}

class RawBodyError extends Error {
  constructor(framework: string, hint: string) {
    super(`notific.me webhook (${framework}): the raw request body is not available. ${hint}`);
    this.name = "RawBodyError";
  }
}

// ---------------------------------------------------------------------------
// Express
// ---------------------------------------------------------------------------

export interface ExpressRequestLike {
  body?: unknown;
  headers: Record<string, string | string[] | undefined>;
  notificme?: NotificMeWebhook<readonly Question[]>;
}

export interface ExpressResponseLike {
  status(code: number): ExpressResponseLike;
  json(body: unknown): unknown;
}

/** Webhook stored by `expressWebhook` on `req.notificme` (typed accessor). */
export function expressWebhookOf<Qs extends readonly Question[] = readonly Question[]>(
  req: unknown,
): NotificMeWebhook<Qs> | undefined {
  return (req as { notificme?: NotificMeWebhook<Qs> }).notificme;
}

/** Default limit for a webhook body read from the stream (1 MiB). */
export const DEFAULT_MAX_BODY_BYTES = 1 << 20;

class BodyTooLargeError extends Error {
  constructor(limit: number) {
    super(`notific.me webhook: request body exceeds ${limit} bytes.`);
    this.name = "BodyTooLargeError";
  }
}

async function readStream(req: unknown, limit: number): Promise<Uint8Array | undefined> {
  if (!req || typeof (req as AsyncIterable<unknown>)[Symbol.asyncIterator] !== "function") return undefined;
  if ((req as { readableEnded?: boolean }).readableEnded) return undefined;
  const declared = Number((req as { headers?: Record<string, unknown> }).headers?.["content-length"]);
  if (Number.isFinite(declared) && declared > limit) throw new BodyTooLargeError(limit);
  const chunks: Uint8Array[] = [];
  let size = 0;
  for await (const chunk of req as AsyncIterable<unknown>) {
    const c = typeof chunk === "string" ? utf8(chunk) : (chunk as Uint8Array);
    size += c.length;
    // Chunked bodies have no Content-Length: stop reading as soon as the limit is passed.
    if (size > limit) throw new BodyTooLargeError(limit);
    chunks.push(c);
  }
  const out = new Uint8Array(size);
  let off = 0;
  for (const c of chunks) {
    out.set(c, off);
    off += c.length;
  }
  return out;
}

/**
 * Express middleware. Put it on the webhook route **before** any JSON body
 * parser (or use `express.raw({ type: "application/json" })`): the signature
 * covers the exact bytes sent.
 *
 * On success the verified webhook is in `req.notificme` and `next()` is called.
 *
 * ```ts
 * app.post("/hooks/notificme", expressWebhook({ key: notific.keysUrl }), (req, res) => {
 *   const reply = req.notificme!.reply;
 *   res.sendStatus(204);
 * });
 * ```
 */
export function expressWebhook<const Qs extends readonly Question[] = readonly Question[]>(
  options: WebhookHelperOptions<Qs>,
): (req: ExpressRequestLike, res: ExpressResponseLike, next: (err?: unknown) => void) => Promise<void> {
  return async (req, res, next) => {
    try {
      let raw = asRaw(req.body);
      if (raw === undefined) {
        if (req.body !== undefined && req.body !== null && !(typeof req.body === "object" && Object.keys(req.body).length === 0)) {
          throw new RawBodyError("express", 'A JSON body parser ran first; use express.raw({ type: "application/json" }) on this route.');
        }
        raw = await readStream(req, options.maxBodyBytes ?? DEFAULT_MAX_BODY_BYTES);
        if (raw === undefined) {
          throw new RawBodyError("express", 'Use express.raw({ type: "application/json" }) on this route.');
        }
      }
      req.notificme = (await receiveWebhook(raw, req.headers, options.key, options)) as NotificMeWebhook<readonly Question[]>;
    } catch (err) {
      if (err instanceof WebhookVerificationError) {
        options.onInvalid?.(err);
        res.status(401).json(rejectionBody(err));
        return;
      }
      if (err instanceof BodyTooLargeError) {
        res.status(413).json({ error: { code: "payload_too_large", message: err.message } });
        return;
      }
      next(err);
      return;
    }
    next();
  };
}

// ---------------------------------------------------------------------------
// Fastify
// ---------------------------------------------------------------------------

export interface FastifyRequestLike {
  body?: unknown;
  rawBody?: unknown;
  headers: Record<string, string | string[] | undefined>;
}

export interface FastifyReplyLike {
  code(statusCode: number): FastifyReplyLike;
  send(payload?: unknown): unknown;
}

/** Webhook stored by the Fastify helpers on `request.notificme`. */
export function fastifyWebhookOf<Qs extends readonly Question[] = readonly Question[]>(
  request: unknown,
): NotificMeWebhook<Qs> | undefined {
  return (request as { notificme?: NotificMeWebhook<Qs> }).notificme;
}

/**
 * Fastify `preHandler` hook. The raw body must reach the route: register
 * `fastifyWebhookPlugin` (which does it for you), use a plugin that sets
 * `request.rawBody`, or a buffer content type parser.
 *
 * On success the verified webhook is in `request.notificme`.
 */
export function fastifyWebhook<const Qs extends readonly Question[] = readonly Question[]>(
  options: WebhookHelperOptions<Qs>,
): (request: FastifyRequestLike, reply: FastifyReplyLike) => Promise<void> {
  return async (request, reply) => {
    const raw = asRaw(request.rawBody) ?? asRaw(request.body);
    if (raw === undefined) {
      throw new RawBodyError("fastify", "Register fastifyWebhookPlugin or add a buffer content type parser.");
    }
    try {
      (request as { notificme?: unknown }).notificme = await receiveWebhook(raw, request.headers as HeadersLike, options.key, options);
    } catch (err) {
      if (err instanceof WebhookVerificationError) {
        options.onInvalid?.(err);
        await reply.code(401).send(rejectionBody(err));
        return;
      }
      throw err;
    }
  };
}

export interface FastifyInstanceLike {
  addContentTypeParser(
    contentType: string,
    opts: { parseAs: "buffer" },
    parser: (request: unknown, body: Uint8Array | string, done: (err: Error | null, body?: unknown) => void) => void,
  ): unknown;
  post(path: string, opts: { preHandler?: unknown }, handler: (request: never, reply: never) => unknown): unknown;
}

export interface FastifyWebhookPluginOptions<Qs extends readonly Question[] = readonly Question[]>
  extends WebhookHelperOptions<Qs> {
  /** Route path (default `"/"`, relative to the plugin prefix). */
  path?: string;
  /**
   * Handles a verified webhook. Return a value to send it as the response;
   * when it returns `undefined` the plugin answers `204`.
   */
  handler: (webhook: NotificMeWebhook<Qs>, request: FastifyRequestLike, reply: FastifyReplyLike) => unknown;
}

/**
 * Fastify plugin that registers `POST <path>` receiving notific.me webhooks.
 * It keeps the raw body (buffer parser scoped to the plugin) and rejects
 * invalid signatures with 401.
 *
 * ```ts
 * await app.register(fastifyWebhookPlugin, {
 *   prefix: "/hooks/notificme",
 *   key: notific.keysUrl,
 *   handler: async (webhook) => { console.log(webhook.reply?.asText); },
 * });
 * ```
 */
export async function fastifyWebhookPlugin<const Qs extends readonly Question[] = readonly Question[]>(
  fastify: FastifyInstanceLike,
  options: FastifyWebhookPluginOptions<Qs>,
): Promise<void> {
  fastify.addContentTypeParser("application/json", { parseAs: "buffer" }, (_req, body, done) => done(null, body));
  const preHandler = fastifyWebhook(options);
  fastify.post(options.path ?? "/", { preHandler }, (async (request: FastifyRequestLike, reply: FastifyReplyLike) => {
    const webhook = fastifyWebhookOf<Qs>(request)!;
    const out = await options.handler(webhook, request, reply);
    if (out !== undefined) return out;
    await reply.code(204).send();
    return reply;
  }) as (request: never, reply: never) => unknown);
}

// ---------------------------------------------------------------------------
// Hono
// ---------------------------------------------------------------------------

export interface HonoContextLike {
  req: {
    arrayBuffer(): Promise<ArrayBuffer>;
    header(name: string): string | undefined;
  };
  set(key: "notificme", value: NotificMeWebhook<readonly Question[]>): void;
}

/**
 * Hono middleware. On success the verified webhook is available with
 * `c.get("notificme")`; invalid signatures get 401.
 *
 * ```ts
 * app.post("/hooks/notificme", honoWebhook({ key: notific.keysUrl }), (c) => {
 *   const webhook = c.get("notificme");
 *   return c.body(null, 204);
 * });
 * ```
 *
 * For typed `c.get`, declare `Variables: { notificme: NotificMeWebhook }` in your Hono env.
 */
export function honoWebhook<const Qs extends readonly Question[] = readonly Question[]>(
  options: WebhookHelperOptions<Qs>,
): (c: HonoContextLike, next: () => Promise<void>) => Promise<Response | void> {
  return async (c, next) => {
    const raw = new Uint8Array(await c.req.arrayBuffer());
    const headers = { get: (name: string) => c.req.header(name) ?? null };
    let webhook: NotificMeWebhook<Qs>;
    try {
      webhook = await receiveWebhook(raw, headers, options.key, options);
    } catch (err) {
      if (err instanceof WebhookVerificationError) {
        options.onInvalid?.(err);
        return new Response(JSON.stringify(rejectionBody(err)), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw err;
    }
    c.set("notificme", webhook as NotificMeWebhook<readonly Question[]>);
    await next();
  };
}
