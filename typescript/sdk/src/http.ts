import { NotificMeApiError, NotificMeError } from "./errors";
import { sleep } from "./util";

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export interface HttpConfig {
  baseUrl: string;
  token: string;
  fetch: FetchLike;
  timeoutMs: number;
  maxRetries: number;
  retryBaseDelayMs: number;
  retryMaxDelayMs: number;
  userAgent: string | undefined;
}

export interface HttpRequest {
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  path: string;
  query?: Record<string, string | number | undefined>;
  body?: unknown;
  headers?: Record<string, string>;
  timeoutMs?: number | undefined;
  signal?: AbortSignal | undefined;
}

export interface HttpResponse<T> {
  status: number;
  headers: Headers;
  data: T;
}

/** Statuses retried automatically. */
const RETRY_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);

function buildUrl(base: string, path: string, query?: HttpRequest["query"]): string {
  const url = base.replace(/\/+$/, "") + path;
  if (!query) return url;
  const qs = Object.entries(query)
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join("&");
  return qs ? `${url}?${qs}` : url;
}

/** Parses `Retry-After` (seconds or HTTP date) into seconds. */
export function parseRetryAfter(value: string | null, now = Date.now()): number | undefined {
  if (!value) return undefined;
  const n = Number(value);
  if (Number.isFinite(n)) return Math.max(0, n);
  const at = Date.parse(value);
  if (Number.isNaN(at)) return undefined;
  return Math.max(0, (at - now) / 1000);
}

function backoff(cfg: HttpConfig, attempt: number, retryAfter?: number): number {
  if (retryAfter !== undefined) return Math.min(retryAfter * 1000, Math.max(cfg.retryMaxDelayMs, 60_000));
  const exp = Math.min(cfg.retryMaxDelayMs, cfg.retryBaseDelayMs * 2 ** attempt);
  // Full jitter between 50% and 100% of the exponential delay.
  return Math.round(exp / 2 + Math.random() * (exp / 2));
}

async function readError(res: Response): Promise<NotificMeApiError> {
  let code = `http_${res.status}`;
  let serverMessage: string | undefined;
  let details: unknown;
  let requestId = res.headers.get("x-request-id") ?? undefined;
  try {
    const text = await res.text();
    if (text) {
      const body = JSON.parse(text) as {
        error?: { code?: string; message?: string; details?: unknown };
        request_id?: string;
      };
      if (body.error?.code) code = body.error.code;
      serverMessage = body.error?.message;
      details = body.error?.details;
      requestId = body.request_id ?? requestId;
    }
  } catch {
    // Body is not JSON: keep the generic code.
  }
  return new NotificMeApiError({
    status: res.status,
    code,
    serverMessage,
    details,
    requestId,
    retryAfter: parseRetryAfter(res.headers.get("retry-after")),
  });
}

/**
 * Performs one API call with timeout and automatic retries (429, 5xx and
 * network errors, honouring `Retry-After`).
 */
export async function request<T>(cfg: HttpConfig, req: HttpRequest): Promise<HttpResponse<T>> {
  const url = buildUrl(cfg.baseUrl, req.path, req.query);
  const headers: Record<string, string> = {
    Accept: "application/json",
    Authorization: `Bearer ${cfg.token}`,
    ...req.headers,
  };
  if (cfg.userAgent) headers["User-Agent"] = cfg.userAgent;
  let body: string | undefined;
  if (req.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(req.body);
  }
  const timeoutMs = req.timeoutMs ?? cfg.timeoutMs;

  for (let attempt = 0; ; attempt++) {
    if (req.signal?.aborted) throw abortError(req.signal);
    const ctrl = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      ctrl.abort();
    }, timeoutMs);
    const onAbort = () => ctrl.abort();
    req.signal?.addEventListener("abort", onAbort, { once: true });

    let res: Response;
    try {
      res = await cfg.fetch(url, { method: req.method, headers, body, signal: ctrl.signal });
    } catch (err) {
      clearTimeout(timer);
      req.signal?.removeEventListener("abort", onAbort);
      if (req.signal?.aborted) throw abortError(req.signal);
      const e = timedOut
        ? new NotificMeError("timeout", `Request timed out after ${timeoutMs} ms: ${req.method} ${req.path}`, { cause: err })
        : new NotificMeError("network_error", `Network error: ${req.method} ${req.path}: ${errMessage(err)}`, { cause: err });
      if (attempt < cfg.maxRetries) {
        await sleep(backoff(cfg, attempt), req.signal).catch(() => {
          throw abortError(req.signal!);
        });
        continue;
      }
      throw e;
    }

    try {
      if (res.ok) {
        const text = await res.text();
        let data: unknown = undefined;
        if (text) {
          try {
            data = JSON.parse(text);
          } catch (err) {
            throw new NotificMeError("invalid_response", `Response is not JSON: ${req.method} ${req.path}`, { cause: err });
          }
        }
        return { status: res.status, headers: res.headers, data: data as T };
      }
      const apiErr = await readError(res);
      if (RETRY_STATUS.has(res.status) && attempt < cfg.maxRetries) {
        await sleep(backoff(cfg, attempt, apiErr.retryAfter), req.signal).catch(() => {
          throw abortError(req.signal!);
        });
        continue;
      }
      throw apiErr;
    } catch (err) {
      if (req.signal?.aborted && !(err instanceof NotificMeApiError)) throw abortError(req.signal);
      if (timedOut && !(err instanceof NotificMeError)) {
        throw new NotificMeError("timeout", `Request timed out after ${timeoutMs} ms: ${req.method} ${req.path}`, { cause: err });
      }
      throw err;
    } finally {
      clearTimeout(timer);
      req.signal?.removeEventListener("abort", onAbort);
    }
  }
}

function abortError(signal: AbortSignal): NotificMeError {
  return new NotificMeError("aborted", "The operation was aborted", { cause: signal.reason });
}

function errMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
