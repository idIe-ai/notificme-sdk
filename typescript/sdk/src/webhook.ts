/**
 * Verification of webhooks sent by the notific.me server (signed replies and
 * other outbox topics).
 *
 *   Notificme-Signature: t=<unix>,kid=<key id>,sig=<base64url(Ed25519(t + "." + body))>
 *   Notificme-Webhook-Id: <id>  (same on every attempt: use it to deduplicate)
 *   Notificme-Topic: <topic>    (e.g. "reply")
 *   Notificme-Attempt: <n>
 *
 * The public key is published at `GET <baseUrl>/.well-known/notificme-keys.json`
 * (JWKS with OKP/Ed25519 keys).
 */

import { WebhookVerificationError } from "./errors";
import type { FetchLike } from "./http";
import type { AskResult, Question } from "./types";
import { fromUtf8, isPlainObject, utf8 } from "./util";
import { toAskResult } from "./wire";

export const WEBHOOK_HEADERS = {
  signature: "Notificme-Signature",
  webhookId: "Notificme-Webhook-Id",
  topic: "Notificme-Topic",
  attempt: "Notificme-Attempt",
} as const;

/** Default accepted clock skew between the server and you: 5 minutes. */
export const DEFAULT_TOLERANCE_SECONDS = 300;

/** Path of the server's public keys (JWKS). */
export const KEYS_PATH = "/.well-known/notificme-keys.json";

/** Ed25519 public key in JWK form, as published by the server. */
export interface Ed25519Jwk {
  kty: "OKP";
  crv: "Ed25519";
  /** base64url of the 32-byte public key. */
  x: string;
  kid?: string;
  alg?: string;
  use?: string;
}

export interface Jwks {
  keys: Ed25519Jwk[];
}

/**
 * The server's public key, in any of these forms:
 * - a URL string (`https://…/.well-known/notificme-keys.json`) or `{ keysUrl }`: the keys are fetched and cached;
 * - the raw 32-byte key (`Uint8Array`) or its base64url/base64 string;
 * - a JWK (`{ kty: "OKP", crv: "Ed25519", x }`) or a JWKS (`{ keys: [...] }`).
 */
export type WebhookKey =
  | string
  | Uint8Array
  | Ed25519Jwk
  | Jwks
  | { keysUrl: string; fetch?: FetchLike };

export interface VerifyOptions {
  /** Maximum clock skew in seconds (default 300). `0` disables the check. */
  toleranceSeconds?: number;
  /** Current time, for tests. */
  now?: Date | number;
}

export interface VerifiedSignature {
  /** Signature timestamp (unix seconds). */
  timestamp: number;
  /** Id of the key that signed it, when the header carries one. */
  keyId?: string;
}

export type RawBody = string | Uint8Array | ArrayBuffer;

/** A verified webhook. */
export interface NotificMeWebhook<Qs extends readonly Question[] = readonly Question[]> extends VerifiedSignature {
  /** `Notificme-Webhook-Id`: identical on every retry; use it to deduplicate. */
  id: string | undefined;
  /** `Notificme-Topic`, e.g. `reply`. */
  topic: string | undefined;
  /** `Notificme-Attempt` (1, 2, …). */
  attempt: number | undefined;
  /** The raw body as text. */
  rawBody: string;
  /** The parsed JSON body (snake_case, as sent). */
  payload: unknown;
  /** For the `reply` topic: the reply as an `AskResult`. */
  reply?: AskResult<Qs>;
}

/** Plain headers object (Node/Express/Fastify) or a fetch `Headers`. */
export type HeadersLike =
  | { get(name: string): string | null }
  | Record<string, string | string[] | undefined>;

export function getHeader(headers: HeadersLike | undefined, name: string): string | undefined {
  if (!headers) return undefined;
  if (typeof (headers as { get?: unknown }).get === "function") {
    return (headers as { get(name: string): string | null }).get(name) ?? undefined;
  }
  const h = headers as Record<string, string | string[] | undefined>;
  const lower = name.toLowerCase();
  let v = h[lower] ?? h[name];
  if (v === undefined) {
    for (const k of Object.keys(h)) {
      if (k.toLowerCase() === lower) {
        v = h[k];
        break;
      }
    }
  }
  return Array.isArray(v) ? v[0] : v;
}

// ---------------------------------------------------------------------------
// base64 / bytes
// ---------------------------------------------------------------------------

function base64ToBytes(s: string): Uint8Array | undefined {
  const clean = s.trim().replace(/=+$/, "").replace(/-/g, "+").replace(/_/g, "/");
  if (!/^[A-Za-z0-9+/]*$/.test(clean) || clean.length % 4 === 1) return undefined;
  const padded = clean + "===".slice((clean.length + 3) % 4);
  try {
    const bin = atob(padded);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return undefined;
  }
}

function toBytes(body: RawBody): Uint8Array {
  if (typeof body === "string") return utf8(body);
  if (body instanceof Uint8Array) return body;
  if (body instanceof ArrayBuffer) return new Uint8Array(body);
  throw new TypeError("rawBody must be a string, Uint8Array/Buffer or ArrayBuffer");
}

// ---------------------------------------------------------------------------
// WebCrypto
// ---------------------------------------------------------------------------

let subtlePromise: Promise<SubtleCrypto> | undefined;

function getSubtle(): Promise<SubtleCrypto> {
  const c = (globalThis as { crypto?: Crypto }).crypto;
  if (c?.subtle) return Promise.resolve(c.subtle);
  // Node 18 does not expose `globalThis.crypto` by default.
  subtlePromise ??= (async () => {
    const name = "node:crypto";
    const mod = (await import(/* @vite-ignore */ /* webpackIgnore: true */ name)) as { webcrypto?: Crypto };
    if (!mod.webcrypto?.subtle) throw new Error("WebCrypto is not available in this runtime");
    return mod.webcrypto.subtle;
  })();
  return subtlePromise;
}

const keyCache = new Map<string, Promise<CryptoKey>>();

function importKey(raw: Uint8Array): Promise<CryptoKey> {
  const id = Array.from(raw, (b) => b.toString(16).padStart(2, "0")).join("");
  let p = keyCache.get(id);
  if (!p) {
    p = getSubtle().then((s) => s.importKey("raw", raw as BufferSource, { name: "Ed25519" }, false, ["verify"]));
    p.catch(() => keyCache.delete(id));
    keyCache.set(id, p);
  }
  return p;
}

// ---------------------------------------------------------------------------
// Key resolution
// ---------------------------------------------------------------------------

interface CachedJwks {
  keys: Ed25519Jwk[];
  fetchedAt: number;
}

const JWKS_TTL_MS = 10 * 60_000;
const JWKS_REFRESH_MIN_MS = 30_000;
const jwksCache = new Map<string, CachedJwks>();
const jwksInflight = new Map<string, Promise<CachedJwks>>();

/** Clears the cache of keys fetched from `keysUrl` (useful in tests). */
export function clearWebhookKeyCache(): void {
  jwksCache.clear();
  jwksInflight.clear();
  keyCache.clear();
}

async function fetchJwks(url: string, f: FetchLike | undefined): Promise<CachedJwks> {
  let p = jwksInflight.get(url);
  if (!p) {
    p = (async () => {
      const doFetch = f ?? ((globalThis as { fetch?: FetchLike }).fetch as FetchLike | undefined);
      if (!doFetch) throw new WebhookVerificationError("unknown_key", "fetch is not available to download the keys");
      let res: Response;
      try {
        res = await doFetch(url, { headers: { Accept: "application/json" } });
      } catch (err) {
        throw new WebhookVerificationError("unknown_key", `Could not download the keys from ${url}: ${String(err)}`);
      }
      if (!res.ok) throw new WebhookVerificationError("unknown_key", `Could not download the keys from ${url}: HTTP ${res.status}`);
      const doc = (await res.json()) as unknown;
      const keys = jwksKeys(doc);
      const cached = { keys, fetchedAt: Date.now() };
      jwksCache.set(url, cached);
      return cached;
    })().finally(() => jwksInflight.delete(url));
    jwksInflight.set(url, p);
  }
  return p;
}

function isJwk(v: unknown): v is Ed25519Jwk {
  return isPlainObject(v) && v.kty === "OKP" && v.crv === "Ed25519" && typeof v.x === "string";
}

function jwksKeys(doc: unknown): Ed25519Jwk[] {
  if (isPlainObject(doc) && Array.isArray(doc.keys)) return doc.keys.filter(isJwk);
  if (isJwk(doc)) return [doc];
  return [];
}

function pickJwk(keys: Ed25519Jwk[], kid: string | undefined): Ed25519Jwk | undefined {
  if (kid) {
    const exact = keys.find((k) => k.kid === kid);
    if (exact) return exact;
    // A key without kid matches any header.
    return keys.length === 1 && !keys[0]!.kid ? keys[0] : undefined;
  }
  return keys[0];
}

function jwkBytes(jwk: Ed25519Jwk): Uint8Array {
  const raw = base64ToBytes(jwk.x);
  if (!raw || raw.length !== 32) throw new WebhookVerificationError("invalid_key", "Public key is not a 32-byte Ed25519 key");
  return raw;
}

async function resolveKey(key: WebhookKey, kid: string | undefined): Promise<Uint8Array> {
  if (key instanceof Uint8Array) {
    if (key.length !== 32) throw new WebhookVerificationError("invalid_key", "Public key must have 32 bytes");
    return key;
  }
  if (typeof key === "string") {
    if (/^https?:\/\//i.test(key)) return resolveKey({ keysUrl: key }, kid);
    const raw = base64ToBytes(key);
    if (!raw || raw.length !== 32) throw new WebhookVerificationError("invalid_key", "Public key must be the base64url of 32 bytes");
    return raw;
  }
  if ("keysUrl" in key) {
    const url = key.keysUrl;
    let cached = jwksCache.get(url);
    if (!cached || Date.now() - cached.fetchedAt > JWKS_TTL_MS) cached = await fetchJwks(url, key.fetch);
    let jwk = pickJwk(cached.keys, kid);
    if (!jwk && Date.now() - cached.fetchedAt > JWKS_REFRESH_MIN_MS) {
      // Unknown kid: the server may have rotated its key.
      cached = await fetchJwks(url, key.fetch);
      jwk = pickJwk(cached.keys, kid);
    }
    if (!jwk) throw new WebhookVerificationError("unknown_key", `No public key with kid ${JSON.stringify(kid)} at ${url}`);
    return jwkBytes(jwk);
  }
  const keys = jwksKeys(key);
  if (keys.length === 0) throw new WebhookVerificationError("invalid_key", "Unsupported public key format");
  const jwk = pickJwk(keys, kid);
  if (!jwk) throw new WebhookVerificationError("unknown_key", `No public key with kid ${JSON.stringify(kid)}`);
  return jwkBytes(jwk);
}

// ---------------------------------------------------------------------------
// Verification
// ---------------------------------------------------------------------------

/** Parsed `Notificme-Signature` header. */
export interface ParsedSignatureHeader {
  t: string;
  kid?: string;
  sig: string;
}

/** Parses `t=…,kid=…,sig=…` (unknown parts are ignored). */
export function parseSignatureHeader(header: string): ParsedSignatureHeader | undefined {
  let t: string | undefined;
  let kid: string | undefined;
  let sig: string | undefined;
  for (const part of header.split(",")) {
    const p = part.trim();
    const i = p.indexOf("=");
    if (i < 0) continue;
    const k = p.slice(0, i);
    const v = p.slice(i + 1);
    if (k === "t") t = v;
    else if (k === "kid") kid = v;
    else if (k === "sig") sig = v;
  }
  if (!t || !sig) return undefined;
  return kid ? { t, kid, sig } : { t, sig };
}

/**
 * Verifies the `Notificme-Signature` header of a webhook against the exact
 * raw body. Throws `WebhookVerificationError` when the header is missing or
 * malformed, the timestamp is outside the tolerance window, the key is
 * unknown, or the signature does not match.
 *
 * Always pass the raw bytes as received: a re-serialized JSON body will not
 * match the signature.
 */
export async function verifyWebhook(
  rawBody: RawBody,
  signatureHeader: string | null | undefined,
  key: WebhookKey,
  options: VerifyOptions = {},
): Promise<VerifiedSignature> {
  if (!signatureHeader) {
    throw new WebhookVerificationError("missing_header", `Missing ${WEBHOOK_HEADERS.signature} header`);
  }
  const parsed = parseSignatureHeader(signatureHeader);
  if (!parsed) {
    throw new WebhookVerificationError("malformed_header", `Malformed ${WEBHOOK_HEADERS.signature} header`);
  }
  if (!/^-?\d+$/.test(parsed.t)) {
    throw new WebhookVerificationError("malformed_header", "Invalid signature timestamp");
  }
  const timestamp = Number(parsed.t);
  const tolerance = options.toleranceSeconds ?? DEFAULT_TOLERANCE_SECONDS;
  if (tolerance > 0) {
    const nowMs = options.now === undefined ? Date.now() : typeof options.now === "number" ? options.now : options.now.getTime();
    const skew = nowMs / 1000 - timestamp;
    if (skew > tolerance || skew < -tolerance) {
      throw new WebhookVerificationError("timestamp_out_of_tolerance", "Signature timestamp is outside the tolerance window");
    }
  }
  const sig = base64ToBytes(parsed.sig);
  if (!sig || sig.length !== 64) {
    throw new WebhookVerificationError("invalid_signature", "Signature is not a base64url Ed25519 signature");
  }
  const pub = await resolveKey(key, parsed.kid);
  const body = toBytes(rawBody);
  const prefix = utf8(parsed.t + ".");
  const msg = new Uint8Array(prefix.length + body.length);
  msg.set(prefix, 0);
  msg.set(body, prefix.length);

  let ok = false;
  try {
    const subtle = await getSubtle();
    const cryptoKey = await importKey(pub);
    ok = await subtle.verify({ name: "Ed25519" }, cryptoKey, sig as BufferSource, msg as BufferSource);
  } catch (err) {
    throw new WebhookVerificationError("invalid_key", `Could not verify the signature: ${String(err)}`);
  }
  if (!ok) throw new WebhookVerificationError("invalid_signature", "Signature does not match the body");
  return parsed.kid ? { timestamp, keyId: parsed.kid } : { timestamp };
}

export interface ReceiveWebhookOptions<Qs extends readonly Question[] = readonly Question[]> extends VerifyOptions {
  /** Questions of the ask, to type the answers of a `reply` webhook. */
  questions?: Qs;
}

/**
 * Verifies a webhook and parses it: headers, JSON payload and, for the
 * `reply` topic, a typed `AskResult`.
 */
export async function receiveWebhook<const Qs extends readonly Question[] = readonly Question[]>(
  rawBody: RawBody,
  headers: HeadersLike,
  key: WebhookKey,
  options: ReceiveWebhookOptions<Qs> = {},
): Promise<NotificMeWebhook<Qs>> {
  const verified = await verifyWebhook(rawBody, getHeader(headers, WEBHOOK_HEADERS.signature), key, options);
  const text = typeof rawBody === "string" ? rawBody : fromUtf8(toBytes(rawBody));
  let payload: unknown;
  try {
    payload = text ? JSON.parse(text) : undefined;
  } catch {
    payload = undefined;
  }
  const attempt = getHeader(headers, WEBHOOK_HEADERS.attempt);
  const topic = getHeader(headers, WEBHOOK_HEADERS.topic);
  const webhook: NotificMeWebhook<Qs> = {
    ...verified,
    id: getHeader(headers, WEBHOOK_HEADERS.webhookId),
    topic,
    attempt: attempt !== undefined && /^\d+$/.test(attempt) ? Number(attempt) : undefined,
    rawBody: text,
    payload,
  };
  if (topic === "reply" && isPlainObject(payload) && Array.isArray(payload.answers)) {
    webhook.reply = toAskResult(payload, options.questions);
  }
  return webhook;
}

/**
 * Same as `receiveWebhook` for a fetch API `Request` (Hono, Next.js route
 * handlers, Cloudflare Workers, Deno, Bun…).
 */
export async function receiveWebhookRequest<const Qs extends readonly Question[] = readonly Question[]>(
  req: Request,
  key: WebhookKey,
  options: ReceiveWebhookOptions<Qs> = {},
): Promise<NotificMeWebhook<Qs>> {
  const body = new Uint8Array(await req.arrayBuffer());
  return receiveWebhook(body, req.headers, key, options);
}
