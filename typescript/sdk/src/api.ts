/**
 * Wire (snake_case) shapes of the notific.me HTTP API v0 that the SDK reads.
 * They mirror protocol/schemas/reply.schema.json and the API responses of
 * `POST /v0/events`, `POST|GET /v0/asks` and `POST /v0/member-tokens`.
 * Internal: not exported.
 */

import type { AuthMethod, DecisionRule, JsonValue, Level } from "./types";

type Uuid = string;
/** RFC 3339 timestamp, UTC (e.g. `2026-11-04T12:00:00Z`). */
type Timestamp = string;

export type ReplyStatus = "answered" | "timeout" | "rejected";

export interface ReplyAnswer {
  question_id: string;
  question_text: string;
  value?: JsonValue;
  /** Free text typed by a human. Untrusted input. */
  free_text?: string;
  audio_ref?: string;
}

export interface WireAnsweredBy {
  identity: string;
  device_id: string;
  level_used: Level;
  auth_method?: AuthMethod;
  group?: string;
  authority_level?: string;
}

export interface WireReplyDecision {
  rule: DecisionRule;
  required?: number;
  eligible: number;
  approvals: number;
  rejections: number;
  outcome?: "approved" | "rejected";
  by?: string[];
  escalation_step?: number;
}

export interface WireVote {
  identity: string;
  handle?: string;
  approve: boolean;
  answered_at: Timestamp;
  level_used?: Level;
  auth_method?: AuthMethod;
  group?: string;
  authority_level?: string;
}

/** `decision` of `GET /v0/asks/{id}` (partial state). */
export interface WireDecisionView {
  rule: DecisionRule;
  required: number;
  eligible: number;
  approvals: number;
  rejections: number;
  pending: number;
  outcome?: "approved" | "rejected";
  by?: string[];
  votes: { handle?: string; display_name: string; approve: boolean; at: Timestamp; group?: string; authority_level?: string }[];
  escalation_step: number;
  escalation_steps: number;
  next_escalation_at?: Timestamp;
}

export interface WireReplySignature {
  alg: "ES256" | "Ed25519";
  key_id?: string;
  value: string;
}

/** Final signed reply delivered to the origin system. */
export interface Reply {
  question_set_id: string;
  version: number;
  status: ReplyStatus;
  answers: ReplyAnswer[];
  as_text?: string;
  answered_by: WireAnsweredBy;
  answered_at?: Timestamp;
  content_hash?: string;
  decision?: WireReplyDecision;
  votes?: WireVote[];
  e_signature?: WireESignature;
  face_verification?: WireFaceVerification;
  signature: WireReplySignature;
}

/** `reply.face_verification` (reply.schema.json). */
export interface WireFaceVerification {
  result: "verified" | "fallback";
  reason?: "attempts_exhausted" | "not_enrolled";
  verification_id?: string;
  model?: string;
  verified_at: Timestamp;
}

/** `reply.e_signature` (reply.schema.json). */
export interface WireESignature {
  name: string;
  initials: string;
  method: "typed" | "drawn";
  style?: string;
  signature_png: string;
  signature_sha256: string;
  initials_png: string;
  initials_sha256: string;
  consent_version?: string;
  adopted_at: Timestamp;
}

/** `POST /v0/events` → 202. */
export interface EventAccepted {
  id: Uuid;
  status: "queued" | "duplicate";
  duplicate_of?: Uuid;
  sender_id?: string;
  received_at: Timestamp;
}

/** `POST /v0/asks` → 201 and `GET /v0/asks/{id}?wait=` → 200. */
export interface AskView {
  id: Uuid;
  status: "open" | "answered" | "timeout" | "expired" | "cancelled";
  version: number;
  level: Level;
  title: string;
  created_at: Timestamp;
  timeout_at?: Timestamp;
  recipients?: string[];
  notification_id?: Uuid;
  content_hash?: string;
  poll_url?: string;
  /** Decision rule state (votes so far, who decided). */
  decision?: WireDecisionView;
  /** The question set asked for the person's electronic signature (only when `true`). */
  require_signature?: boolean;
  /** The question set asked for the person's face to be verified (only when `true`). */
  require_face?: boolean;
  /** Present when `answered` or `timeout`. */
  reply?: Reply;
}

/** Account, as in `POST /v0/auth/verify`. */
export interface WireUser {
  id: Uuid;
  email: string;
  handle: string;
  display_name: string;
  locale: string;
  avatar_url?: string;
  created_at: Timestamp;
}

/** `POST /v0/member-tokens` → 201. */
export interface MemberTokenResponse {
  token: string;
  token_type: "Bearer";
  expires_at: Timestamp;
  workspace_id: Uuid;
  user: WireUser;
  membership_created: boolean;
}

/** `POST /v0/member-tokens/revoke` → 200. */
export interface MemberTokensRevokeResponse {
  revoked: number;
}
