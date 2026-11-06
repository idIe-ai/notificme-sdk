import type { AskView, EventAccepted } from "./api";
import { AskClosedError, AskPendingError, NotificMeError } from "./errors";
import { type FetchLike, type HttpConfig, request } from "./http";
import type {
  AskInfo,
  AskInput,
  AskOptions,
  AskResult,
  EventInput,
  Question,
  RequestOptions,
  SendResult,
} from "./types";
import { env, randomId, sleep } from "./util";
import { VERSION } from "./version";
import {
  KEYS_PATH,
  type NotificMeWebhook,
  type RawBody,
  type HeadersLike,
  type ReceiveWebhookOptions,
  type VerifiedSignature,
  type VerifyOptions,
  receiveWebhook,
  verifyWebhook,
} from "./webhook";
import { askInfoFromWire, askToWire, eventToWire, sendResultFromWire, toAskResult } from "./wire";

/** Default API address. */
export const DEFAULT_BASE_URL = "https://api.notific.me";

/** Longest wait the server accepts per long-poll request (seconds). */
const MAX_POLL_WAIT_SECONDS = 60;
/** Minimum time between two polls when the server answers early. */
const MIN_POLL_INTERVAL_MS = 1000;

export interface NotificMeOptions {
  /**
   * Service token (`nm_svc_…`) created in the customer area. Defaults to the
   * `NOTIFICME_TOKEN` environment variable. Keep it on the server: never ship
   * it to a browser you do not control.
   */
  token?: string;
  /** API address. Defaults to `NOTIFICME_URL` or `https://api.notific.me`. */
  baseUrl?: string;
  /** Default `source` of the events (e.g. `"billing"`). */
  source?: string;
  /** Per-request timeout in ms (default 30 000). */
  timeoutMs?: number;
  /** Automatic retries on 429, 5xx and network errors (default 3). */
  maxRetries?: number;
  /** First retry delay in ms; doubles on every attempt (default 500). */
  retryBaseDelayMs?: number;
  /** Maximum retry delay in ms, unless `Retry-After` asks for more (default 10 000). */
  retryMaxDelayMs?: number;
  /** Custom `fetch` (defaults to the global one). */
  fetch?: FetchLike;
  /** Extra text appended to the `User-Agent` header (Node only; browsers ignore it). */
  userAgent?: string;
}

/** Handle of a question set created with `createAsk()`. */
export interface AskHandle<Qs extends readonly Question[] = readonly Question[]> extends AskInfo {
  /** Waits (long-poll) for the reply. */
  wait(options?: Omit<AskOptions, "idempotencyKey">): Promise<AskResult<Qs>>;
}

interface LiveCardBase {
  source: string;
  type: string;
  severity: EventInput["severity"];
  summary?: string | undefined;
}

/**
 * notific.me client.
 *
 * ```ts
 * const notific = new NotificMe({ token: process.env.NOTIFICME_TOKEN, source: "billing" });
 * await notific.send({ type: "invoice.failed", severity: "high", summary: "Invoice #42 failed" });
 * ```
 */
export class NotificMe {
  readonly baseUrl: string;
  readonly source: string | undefined;
  private readonly http: HttpConfig;
  /** Last event sent per live card, so `update()` can fill required fields. */
  private readonly liveCards = new Map<string, LiveCardBase>();

  constructor(options: NotificMeOptions = {}) {
    const token = options.token ?? env("NOTIFICME_TOKEN");
    if (!token) {
      throw new NotificMeError("missing_token", "A service token is required: pass { token } or set NOTIFICME_TOKEN");
    }
    const f = options.fetch ?? (globalThis as { fetch?: FetchLike }).fetch;
    if (!f) throw new NotificMeError("missing_fetch", "No fetch implementation found: use Node 18+ or pass { fetch }");
    this.baseUrl = (options.baseUrl ?? env("NOTIFICME_URL") ?? DEFAULT_BASE_URL).replace(/\/+$/, "");
    this.source = options.source;
    const isNode = typeof (globalThis as { process?: { versions?: { node?: string } } }).process?.versions?.node === "string";
    this.http = {
      baseUrl: this.baseUrl,
      token,
      fetch: options.fetch ?? ((input, init) => f(input, init)),
      timeoutMs: options.timeoutMs ?? 30_000,
      maxRetries: options.maxRetries ?? 3,
      retryBaseDelayMs: options.retryBaseDelayMs ?? 500,
      retryMaxDelayMs: options.retryMaxDelayMs ?? 10_000,
      userAgent: isNode ? `notificme-sdk-ts/${VERSION}${options.userAgent ? ` ${options.userAgent}` : ""}` : undefined,
    };
  }

  /** URL of the server's public keys, used to verify webhooks. */
  get keysUrl(): string {
    return this.baseUrl + KEYS_PATH;
  }

  // -------------------------------------------------------------------------
  // Events
  // -------------------------------------------------------------------------

  /**
   * Sends an event (`POST /v0/events`). Retries are safe: the SDK sets an
   * idempotency `id` when you do not pass one.
   */
  async send(event: EventInput, options: RequestOptions = {}): Promise<SendResult> {
    const source = event.source ?? this.source;
    if (!source) throw new TypeError("Event `source` is required: pass it in the event or in new NotificMe({ source })");
    if (!event.type) throw new TypeError("Event `type` is required");
    const cardId = event.cardId ?? (event.live ? randomId() : undefined);
    const id = event.id ?? randomId();
    const body = eventToWire({ ...event, id, source, cardId }, new Date());
    const res = await request<EventAccepted>(this.http, {
      method: "POST",
      path: "/v0/events",
      body,
      timeoutMs: options.timeoutMs,
      signal: options.signal,
    });
    if (cardId) {
      this.rememberCard(cardId, {
        source,
        type: event.type,
        severity: event.severity,
        summary: event.summary ?? event.content?.summary,
      });
    }
    return sendResultFromWire(res.data, cardId, res.headers.get("idempotent-replayed") === "true");
  }

  /**
   * Updates a live card: sends a new event with the same `cardId`. Fields you
   * omit (`type`, `severity`, `source`, `summary`) are taken from the last
   * event sent for this card by this client.
   */
  async update(cardId: string, event: Partial<EventInput>, options: RequestOptions = {}): Promise<SendResult> {
    if (!cardId) throw new TypeError("update() needs the cardId returned by send()");
    const base = this.liveCards.get(cardId);
    const type = event.type ?? base?.type;
    if (!type) throw new TypeError("update(): pass `type` (this client has not sent this card before)");
    const merged: EventInput = {
      ...event,
      type,
      source: event.source ?? base?.source ?? this.source,
      severity: event.severity ?? base?.severity,
      cardId,
      live: false,
    };
    if ((merged.card || merged.content) && merged.summary === undefined && !merged.content?.summary && base?.summary) {
      merged.summary = base.summary;
    }
    return this.send(merged, options);
  }

  private rememberCard(cardId: string, base: LiveCardBase): void {
    this.liveCards.delete(cardId);
    this.liveCards.set(cardId, base);
    if (this.liveCards.size > 1000) {
      const oldest = this.liveCards.keys().next().value;
      if (oldest !== undefined) this.liveCards.delete(oldest);
    }
  }

  // -------------------------------------------------------------------------
  // Asks
  // -------------------------------------------------------------------------

  /**
   * Asks humans and waits for the decision (long-poll). Resolves with the
   * signed reply when it is answered, or when it times out (`status:
   * "timeout"`, with `onTimeout` answers applied).
   *
   * Throws `AskClosedError` when the question set is expired or cancelled,
   * and `AskPendingError` when `maxWaitMs` elapses first.
   *
   * To receive the reply on a webhook instead of waiting, use `createAsk()`
   * with `callbackUrl` and verify the request with `receiveWebhook()`.
   */
  async ask<const Qs extends readonly Question[]>(input: AskInput<Qs>, options: AskOptions = {}): Promise<AskResult<Qs>> {
    const handle = await this.createAsk(input, options);
    return handle.wait(options);
  }

  /** Creates the question set (`POST /v0/asks`) without waiting for the reply. */
  async createAsk<const Qs extends readonly Question[]>(
    input: AskInput<Qs>,
    options: AskOptions = {},
  ): Promise<AskHandle<Qs>> {
    if (!input.questions || input.questions.length === 0) throw new TypeError("ask() needs at least one question");
    const res = await request<AskView>(this.http, {
      method: "POST",
      path: "/v0/asks",
      body: askToWire(input),
      headers: { "Idempotency-Key": options.idempotencyKey ?? randomId() },
      timeoutMs: options.timeoutMs,
      signal: options.signal,
    });
    const info = askInfoFromWire(res.data);
    const questions = input.questions;
    return {
      ...info,
      wait: (o: Omit<AskOptions, "idempotencyKey"> = {}) => this.waitForReply(info.id, { ...o, questions }),
    };
  }

  /** Reads a question set (`GET /v0/asks/{id}`), optionally waiting up to `waitSeconds` (max 60) for a change. */
  async getAsk(id: string, options: RequestOptions & { waitSeconds?: number } = {}): Promise<AskInfo & { reply?: unknown }> {
    const wait = Math.max(0, Math.min(MAX_POLL_WAIT_SECONDS, Math.floor(options.waitSeconds ?? 0)));
    const res = await request<AskView>(this.http, {
      method: "GET",
      path: `/v0/asks/${encodeURIComponent(id)}`,
      query: wait > 0 ? { wait } : undefined,
      timeoutMs: options.timeoutMs ?? Math.max(this.http.timeoutMs, wait * 1000 + 15_000),
      signal: options.signal,
    });
    const info: AskInfo & { reply?: unknown } = askInfoFromWire(res.data);
    if (res.data.reply) info.reply = res.data.reply;
    return info;
  }

  /**
   * Waits (long-poll) for the reply of an existing question set. Pass the
   * `questions` used to create it to get typed answers.
   */
  async waitForReply<const Qs extends readonly Question[] = readonly Question[]>(
    id: string,
    options: Omit<AskOptions, "idempotencyKey"> & { questions?: Qs } = {},
  ): Promise<AskResult<Qs>> {
    const deadline = options.maxWaitMs !== undefined ? Date.now() + options.maxWaitMs : undefined;
    for (;;) {
      let waitSeconds = MAX_POLL_WAIT_SECONDS;
      if (deadline !== undefined) {
        const left = deadline - Date.now();
        if (left <= 0) throw new AskPendingError(id);
        waitSeconds = Math.max(1, Math.min(MAX_POLL_WAIT_SECONDS, Math.ceil(left / 1000)));
      }
      const started = Date.now();
      const view = await this.getAsk(id, { waitSeconds, signal: options.signal });
      if (view.reply && (view.status === "answered" || view.status === "timeout")) {
        return toAskResult(view.reply, options.questions);
      }
      if (view.status === "expired" || view.status === "cancelled") throw new AskClosedError(id, view.status);
      if (view.status !== "open") {
        throw new NotificMeError("invalid_response", `Question set ${id} is ${view.status} but has no reply`);
      }
      if (deadline !== undefined && Date.now() >= deadline) throw new AskPendingError(id);
      // A proxy may cut the long-poll short: avoid a tight loop.
      const elapsed = Date.now() - started;
      if (elapsed < MIN_POLL_INTERVAL_MS) {
        let pause = MIN_POLL_INTERVAL_MS - elapsed;
        if (deadline !== undefined) pause = Math.min(pause, Math.max(0, deadline - Date.now()));
        await sleep(pause, options.signal).catch(() => {
          throw new NotificMeError("aborted", "The operation was aborted");
        });
      }
    }
  }

  // -------------------------------------------------------------------------
  // Webhooks
  // -------------------------------------------------------------------------

  /** `verifyWebhook()` using this server's public keys (`keysUrl`). */
  verifyWebhook(rawBody: RawBody, signatureHeader: string | null | undefined, options?: VerifyOptions): Promise<VerifiedSignature> {
    return verifyWebhook(rawBody, signatureHeader, { keysUrl: this.keysUrl, fetch: this.http.fetch }, options);
  }

  /** `receiveWebhook()` using this server's public keys (`keysUrl`). */
  receiveWebhook<const Qs extends readonly Question[] = readonly Question[]>(
    rawBody: RawBody,
    headers: HeadersLike,
    options?: ReceiveWebhookOptions<Qs>,
  ): Promise<NotificMeWebhook<Qs>> {
    return receiveWebhook(rawBody, headers, { keysUrl: this.keysUrl, fetch: this.http.fetch }, options);
  }
}
