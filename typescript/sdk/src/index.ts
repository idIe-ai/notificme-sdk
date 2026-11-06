/**
 * @notificme/sdk: official TypeScript SDK for notific.me.
 *
 * - `new NotificMe({ token })` client: `send()`, `update()` (live cards),
 *   `ask()` / `createAsk()` / `waitForReply()` (human decisions),
 *   `members.issueToken()` / `members.revokeTokens()` (person tokens for your own app);
 * - `verifyWebhook()` / `receiveWebhook()` for signed webhooks, plus helpers
 *   for Express, Fastify and Hono;
 * - `UntrustedText` for free text typed by humans.
 */

export { NotificMe, DEFAULT_BASE_URL } from "./client";
export type { NotificMeOptions, AskHandle } from "./client";
export type { Members } from "./members";
export {
  NotificMeError,
  NotificMeApiError,
  AskClosedError,
  AskPendingError,
  WebhookVerificationError,
} from "./errors";
export type { WebhookVerificationReason } from "./errors";
export { UntrustedText } from "./untrusted";
export { toAskResult } from "./wire";
export { toCamel, toSnake, toCamelKey, toSnakeKey } from "./case";
export {
  verifyWebhook,
  receiveWebhook,
  receiveWebhookRequest,
  parseSignatureHeader,
  clearWebhookKeyCache,
  WEBHOOK_HEADERS,
  DEFAULT_TOLERANCE_SECONDS,
  KEYS_PATH,
} from "./webhook";
export type {
  WebhookKey,
  Ed25519Jwk,
  Jwks,
  VerifyOptions,
  VerifiedSignature,
  NotificMeWebhook,
  ReceiveWebhookOptions,
  RawBody,
  HeadersLike,
  ParsedSignatureHeader,
} from "./webhook";
export { expressWebhook, expressWebhookOf, fastifyWebhook, fastifyWebhookPlugin, fastifyWebhookOf, honoWebhook } from "./frameworks";
export type {
  WebhookHelperOptions,
  FastifyWebhookPluginOptions,
  ExpressRequestLike,
  ExpressResponseLike,
  FastifyRequestLike,
  FastifyReplyLike,
  FastifyInstanceLike,
  HonoContextLike,
} from "./frameworks";
export type { FetchLike } from "./http";
export { VERSION } from "./version";
export type * from "./types";
