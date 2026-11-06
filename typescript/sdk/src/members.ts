import type { MemberTokenResponse, MemberTokensRevokeResponse } from "./api";
import { type HttpConfig, request } from "./http";
import type {
  IssueMemberTokenInput,
  MemberToken,
  RequestOptions,
  RevokeMemberTokensInput,
  RevokeMemberTokensResult,
} from "./types";
import { memberTokenFromWire, memberTokenToWire } from "./wire";

/**
 * Person tokens for your own app (`notific.members`). Your backend issues a
 * short-lived token for one person; your app uses it to show that person's
 * inbox and answer decisions, restricted to your workspace. Needs a service
 * token with the `members:token` scope.
 */
export class Members {
  private readonly http: HttpConfig;

  /** @internal Use `notific.members`. */
  constructor(http: HttpConfig) {
    this.http = http;
  }

  /**
   * Issues a person token (`POST /v0/member-tokens`). The e-mail becomes a
   * member of your workspace when it is not one yet.
   *
   * Throws `NotificMeApiError` with code `workspace_blocked` (403) when the
   * person blocked your workspace, and `forbidden` when the service token
   * lacks the `members:token` scope.
   */
  async issueToken(input: IssueMemberTokenInput, options: RequestOptions = {}): Promise<MemberToken> {
    if (!input?.email) throw new TypeError("issueToken() needs the person's `email`");
    const res = await request<MemberTokenResponse>(this.http, {
      method: "POST",
      path: "/v0/member-tokens",
      body: memberTokenToWire(input),
      timeoutMs: options.timeoutMs,
      signal: options.signal,
    });
    return memberTokenFromWire(res.data);
  }

  /**
   * Revokes every active person token of this e-mail in your workspace
   * (`POST /v0/member-tokens/revoke`), e.g. when the person leaves the company.
   */
  async revokeTokens(input: RevokeMemberTokensInput, options: RequestOptions = {}): Promise<RevokeMemberTokensResult> {
    if (!input?.email) throw new TypeError("revokeTokens() needs the person's `email`");
    const res = await request<MemberTokensRevokeResponse>(this.http, {
      method: "POST",
      path: "/v0/member-tokens/revoke",
      body: { email: input.email },
      timeoutMs: options.timeoutMs,
      signal: options.signal,
    });
    return { revoked: res.data.revoked };
  }
}
