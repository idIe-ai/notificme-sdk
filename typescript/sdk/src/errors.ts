/**
 * Base class of every error thrown by the SDK.
 *
 * `code` is stable and machine-readable. For API errors it is the server's
 * `error.code` (e.g. `validation_failed`, `rate_limited`); for client-side
 * failures it is one of `timeout`, `network_error`, `aborted`,
 * `invalid_response`, `ask_closed`, `ask_pending`, `invalid_signature`.
 */
export class NotificMeError extends Error {
  readonly code: string;

  constructor(code: string, message: string, options?: { cause?: unknown }) {
    super(message);
    this.name = "NotificMeError";
    this.code = code;
    if (options && "cause" in options) {
      Object.defineProperty(this, "cause", { value: options.cause, enumerable: false, configurable: true, writable: true });
    }
  }
}

/** The API answered with a non-2xx status. */
export class NotificMeApiError extends NotificMeError {
  /** HTTP status code. */
  readonly status: number;
  /** Request id echoed by the server (useful when reporting issues). */
  readonly requestId: string | undefined;
  /** Extra details sent by the server (e.g. field errors of `validation_failed`). */
  readonly details: unknown;
  /** Developer-facing message from the server. It may not be in English. */
  readonly serverMessage: string | undefined;
  /** Seconds from the `Retry-After` header, when present. */
  readonly retryAfter: number | undefined;

  constructor(init: {
    status: number;
    code: string;
    serverMessage?: string | undefined;
    details?: unknown;
    requestId?: string | undefined;
    retryAfter?: number | undefined;
  }) {
    const suffix = init.serverMessage ? `: ${init.serverMessage}` : "";
    super(init.code, `notific.me API error ${init.status} (${init.code})${suffix}`);
    this.name = "NotificMeApiError";
    this.status = init.status;
    this.requestId = init.requestId;
    this.details = init.details;
    this.serverMessage = init.serverMessage;
    this.retryAfter = init.retryAfter;
  }
}

/** The question set was closed without a reply (`expired` or `cancelled`). */
export class AskClosedError extends NotificMeError {
  readonly askId: string;
  readonly status: string;

  constructor(askId: string, status: string) {
    super("ask_closed", `Question set ${askId} was closed without a reply (status: ${status})`);
    this.name = "AskClosedError";
    this.askId = askId;
    this.status = status;
  }
}

/**
 * The client stopped waiting (`maxWaitMs` elapsed) while the question set is
 * still open. Resume later with `notific.waitForReply(error.askId)`.
 */
export class AskPendingError extends NotificMeError {
  readonly askId: string;

  constructor(askId: string) {
    super("ask_pending", `Stopped waiting for question set ${askId}; it is still open`);
    this.name = "AskPendingError";
    this.askId = askId;
  }
}

/** Reasons a webhook signature is rejected. */
export type WebhookVerificationReason =
  | "missing_header"
  | "malformed_header"
  | "timestamp_out_of_tolerance"
  | "unknown_key"
  | "invalid_key"
  | "invalid_signature";

/** The `Notificme-Signature` header is missing, malformed or does not match the body. */
export class WebhookVerificationError extends NotificMeError {
  readonly reason: WebhookVerificationReason;

  constructor(reason: WebhookVerificationReason, message: string) {
    super("invalid_signature", message);
    this.name = "WebhookVerificationError";
    this.reason = reason;
  }
}
