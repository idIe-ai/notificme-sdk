/**
 * Wire (snake_case) shapes of the notific.me HTTP API v0 that the SDK reads.
 * They mirror protocol/schemas/reply.schema.json and the API responses of
 * `POST /v0/events` and `POST|GET /v0/asks`. Internal: not exported.
 */

import type { JsonValue, Level } from "./types";

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
  auth_method?: "tap" | "unlock" | "biometric" | "device_pin" | "app_password" | "totp";
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
  signature: WireReplySignature;
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
  /** Present when `answered` or `timeout`. */
  reply?: Reply;
}
