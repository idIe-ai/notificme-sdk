/**
 * Conversion between the public camelCase types and the snake_case wire
 * format. The protocol schemas use `additionalProperties: false`, so fields are
 * mapped one by one instead of converting every key blindly.
 */

import type { AskView, EventAccepted, MemberTokenResponse, Reply, ReplyAnswer, WireUser } from "./api";
import { toSnake } from "./case";
import type {
  AnswerDetail,
  AskInfo,
  AskInput,
  AskResult,
  Card,
  Duration,
  EventInput,
  IssueMemberTokenInput,
  JsonValue,
  MemberToken,
  MemberUser,
  Question,
  SendResult,
  VoiceAnswer,
} from "./types";
import { UntrustedText } from "./untrusted";
import { isPlainObject } from "./util";

function compact<T extends Record<string, unknown>>(obj: T): T {
  for (const k of Object.keys(obj)) if (obj[k] === undefined) delete obj[k];
  return obj;
}

function list(v: string | string[] | undefined): string[] | undefined {
  if (v === undefined) return undefined;
  return Array.isArray(v) ? v : [v];
}

export function duration(v: Duration | undefined): string | undefined {
  if (v === undefined) return undefined;
  if (typeof v === "number") {
    if (!Number.isFinite(v) || v < 0) throw new TypeError(`Invalid duration: ${v}`);
    return `${Math.round(v)}s`;
  }
  return v;
}

function iso(v: Date | string | undefined, fallback: Date): string {
  if (v === undefined) return fallback.toISOString();
  return typeof v === "string" ? v : v.toISOString();
}

export function cardToWire(card: Card | undefined): Record<string, unknown> | undefined {
  if (!card) return undefined;
  return toSnake<Record<string, unknown>>(card);
}

/** EventInput → event.schema.json. */
export function eventToWire(e: EventInput & { id: string; source: string }, now: Date): Record<string, unknown> {
  let content: Record<string, unknown> | undefined;
  if (e.content || e.summary !== undefined || e.card) {
    const summary = e.summary ?? e.content?.summary;
    if (summary === undefined) throw new TypeError("An event with a card needs a `summary`");
    content = compact({
      summary,
      card: cardToWire(e.card ?? e.content?.card),
      rich: e.content?.rich ? compact({ html_ref: e.content.rich.htmlRef, height: e.content.rich.height }) : undefined,
    });
  }
  return compact({
    version: "0",
    id: e.id,
    source: e.source,
    type: e.type,
    severity: e.severity ?? "info",
    occurred_at: iso(e.occurredAt, now),
    to: list(e.to),
    payload: e.payload,
    dedup_key: e.dedupKey,
    ttl: duration(e.ttl),
    expires_when: e.expiresWhen,
    card_id: e.cardId,
    retention: e.retention,
    content,
  });
}

export function sendResultFromWire(r: EventAccepted, cardId: string | undefined, replayed: boolean): SendResult {
  return compact({
    id: r.id,
    status: r.status,
    duplicateOf: r.duplicate_of,
    senderId: r.sender_id,
    receivedAt: r.received_at,
    cardId,
    replayed,
  }) as SendResult;
}

function questionToWire(q: Question): Record<string, unknown> {
  const base = compact({
    id: q.id,
    type: q.type,
    text: q.text,
    optional: q.optional,
    level: q.level,
  }) as Record<string, unknown>;
  if (q.type === "single" || q.type === "multi") {
    base.options = [...q.options];
    if (q.allowOther !== undefined) base.allow_other = q.allowOther;
  }
  return base;
}

/** AskInput → question-set.schema.json. */
export function askToWire(a: AskInput): Record<string, unknown> {
  return compact({
    version: "0",
    to: list(a.to),
    title: a.title,
    context: a.context,
    questions: a.questions.map(questionToWire),
    level: a.level,
    quorum: a.quorum !== undefined ? { required: a.quorum } : undefined,
    offline: a.offline,
    timeout: duration(a.timeout),
    on_timeout: a.onTimeout as Record<string, JsonValue> | undefined,
    escalate: a.escalate,
    callback_url: a.callbackUrl,
    card: cardToWire(a.card),
  });
}

export function askInfoFromWire(v: AskView): AskInfo {
  return compact({
    id: v.id,
    status: v.status,
    version: v.version,
    level: v.level,
    title: v.title,
    createdAt: v.created_at,
    timeoutAt: v.timeout_at,
    recipients: v.recipients,
    notificationId: v.notification_id,
    contentHash: v.content_hash,
    pollUrl: v.poll_url,
  }) as AskInfo;
}

/** IssueMemberTokenInput → `POST /v0/member-tokens` body. */
export function memberTokenToWire(i: IssueMemberTokenInput): Record<string, unknown> {
  return compact({
    email: i.email,
    external_id: i.externalId,
    display_name: i.displayName,
    ttl: duration(i.ttl),
  });
}

function userFromWire(u: WireUser): MemberUser {
  return compact({
    id: u.id,
    email: u.email,
    handle: u.handle,
    displayName: u.display_name,
    locale: u.locale,
    avatarUrl: u.avatar_url || undefined,
    createdAt: u.created_at,
  }) as MemberUser;
}

export function memberTokenFromWire(r: MemberTokenResponse): MemberToken {
  return {
    token: r.token,
    tokenType: r.token_type,
    expiresAt: r.expires_at,
    workspaceId: r.workspace_id,
    user: userFromWire(r.user),
    membershipCreated: r.membership_created,
  };
}

function answerValue(a: ReplyAnswer, q: Question | undefined): unknown {
  const free = a.free_text !== undefined && a.free_text !== "" ? new UntrustedText(a.free_text) : undefined;
  const type = q?.type;
  switch (type) {
    case "text":
      return free ?? (typeof a.value === "string" ? new UntrustedText(a.value) : undefined);
    case "voice": {
      const v: VoiceAnswer = {};
      if (free) v.text = free;
      if (a.audio_ref) v.audioRef = a.audio_ref;
      return free || a.audio_ref ? v : undefined;
    }
    case "single":
      return a.value ?? free;
    case "multi":
      return a.value ?? (free ? [] : undefined);
    case "yesno":
    case "number":
    case "date":
      return a.value;
    default:
      // Unknown question (e.g. a webhook parsed without the questions).
      if (a.value !== undefined && a.value !== null) return a.value;
      if (free) return free;
      if (a.audio_ref) return { audioRef: a.audio_ref } satisfies VoiceAnswer;
      return undefined;
  }
}

/**
 * Turns a reply (reply.schema.json, as delivered by `GET /v0/asks/{id}` or by
 * the `reply` webhook) into a typed `AskResult`. Pass the same `questions`
 * used in `ask()` to get answers typed and converted per question type.
 */
export function toAskResult<const Qs extends readonly Question[] = readonly Question[]>(
  reply: unknown,
  questions?: Qs,
): AskResult<Qs> {
  if (!isPlainObject(reply) || !Array.isArray(reply.answers)) {
    throw new TypeError("Not a notific.me reply: expected an object with `answers`");
  }
  const r = reply as unknown as Reply;
  const byId = new Map<string, Question>();
  for (const q of questions ?? []) byId.set(q.id, q);

  const answers: Record<string, unknown> = {};
  const details: Record<string, AnswerDetail> = {};
  for (const q of questions ?? []) {
    answers[q.id] = undefined;
  }
  for (const a of r.answers) {
    const detail: AnswerDetail = { questionId: a.question_id, questionText: a.question_text };
    if (a.value !== undefined) detail.value = a.value as JsonValue;
    if (a.free_text !== undefined && a.free_text !== "") detail.freeText = new UntrustedText(a.free_text);
    if (a.audio_ref) detail.audioRef = a.audio_ref;
    details[a.question_id] = detail;
    answers[a.question_id] = answerValue(a, byId.get(a.question_id));
  }

  const result = compact({
    id: r.question_set_id,
    version: r.version,
    status: r.status,
    timedOut: r.status === "timeout",
    asText: r.as_text ?? "",
    answeredBy: r.answered_by
      ? compact({
          identity: r.answered_by.identity,
          deviceId: r.answered_by.device_id,
          levelUsed: r.answered_by.level_used,
          authMethod: r.answered_by.auth_method,
        })
      : undefined,
    answeredAt: r.answered_at,
    contentHash: r.content_hash,
    signature: r.signature
      ? compact({ alg: r.signature.alg, keyId: r.signature.key_id, value: r.signature.value })
      : undefined,
    answers,
    details,
    raw: reply,
  });
  return result as unknown as AskResult<Qs>;
}
