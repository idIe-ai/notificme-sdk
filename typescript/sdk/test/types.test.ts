// Type-level tests (checked by `npm run typecheck`) plus the case helpers.
import { describe, expect, expectTypeOf, it } from "vitest";
import {
  type AskResult,
  type AuthMethod,
  type MemberToken,
  type NotificMe,
  type UntrustedText,
  type VoiceAnswer,
  toAskResult,
  toCamel,
  toSnake,
} from "../src/index";

const questions = [
  { id: "action", type: "single", text: "?", options: ["Approve", "Deny"] },
  { id: "other", type: "single", text: "?", options: ["A", "B"], allowOther: true },
  { id: "ok", type: "yesno", text: "?" },
  { id: "tags", type: "multi", text: "?", options: ["x", "y"] },
  { id: "note", type: "text", text: "?", optional: true },
  { id: "voice", type: "voice", text: "?" },
  { id: "n", type: "number", text: "?" },
  { id: "d", type: "date", text: "?" },
] as const;

describe("types", () => {
  it("types answers by question id", () => {
    const r = toAskResult({ answers: [] }, questions);
    expectTypeOf(r).toEqualTypeOf<AskResult<typeof questions>>();
    if (r.status === "answered") {
      expectTypeOf(r.answers.action).toEqualTypeOf<"Approve" | "Deny">();
      expectTypeOf(r.answers.other).toEqualTypeOf<"A" | "B" | UntrustedText>();
      expectTypeOf(r.answers.ok).toEqualTypeOf<boolean>();
      expectTypeOf(r.answers.tags).toEqualTypeOf<("x" | "y")[]>();
      expectTypeOf(r.answers.note).toEqualTypeOf<UntrustedText | undefined>();
      expectTypeOf(r.answers.voice).toEqualTypeOf<VoiceAnswer>();
      expectTypeOf(r.answers.n).toEqualTypeOf<number>();
      expectTypeOf(r.answers.d).toEqualTypeOf<string>();
    } else {
      expectTypeOf(r.answers.ok).toEqualTypeOf<boolean | undefined>();
    }
    expectTypeOf(r.answeredBy?.authMethod).toEqualTypeOf<AuthMethod | undefined>();
    expectTypeOf<"delegated">().toExtend<AuthMethod>();
  });

  it("types person tokens", () => {
    expectTypeOf<NotificMe["members"]["issueToken"]>().returns.resolves.toEqualTypeOf<MemberToken>();
    expectTypeOf<NotificMe["members"]["revokeTokens"]>().returns.resolves.toEqualTypeOf<{ revoked: number }>();
    expectTypeOf<NotificMe["members"]["issueToken"]>().parameter(0).toHaveProperty("ttl");
  });
});

describe("case conversion", () => {
  it("converts keys but not user data", () => {
    const snake = toSnake({ dedupKey: "a", payload: { userId: 1 }, nested: [{ questionId: "q", allowOther: true }] });
    expect(snake).toEqual({ dedup_key: "a", payload: { userId: 1 }, nested: [{ question_id: "q", allow_other: true }] });
    expect(toCamel(snake)).toEqual({ dedupKey: "a", payload: { userId: 1 }, nested: [{ questionId: "q", allowOther: true }] });
    expect(toSnake({ HTMLRef: 1, already_snake: 2 })).toEqual({ html_ref: 1, already_snake: 2 });
  });
});
