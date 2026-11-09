import { afterEach, describe, expect, it } from "vitest";
import { NotificMe, toAskResult } from "../src/index";
import { fakeServer, sendJson } from "./helpers";

type Server = Awaited<ReturnType<typeof fakeServer>>;
let server: Server | undefined;

afterEach(async () => {
  await server?.close();
  server = undefined;
});

const id = "019a0000-0000-7000-8000-0000000000b1";
const questions = [{ id: "ok", type: "yesno", text: "Approve the contract?" }] as const;

const decided = {
  question_set_id: id,
  version: 1,
  status: "rejected",
  answers: [{ question_id: "ok", question_text: "Approve the contract?", value: false }],
  as_text: "Rejected (quorum of 2, 0 of 3 eligible)…",
  answered_by: { identity: "bruno@acme.com", device_id: "dev-2", level_used: 1, auth_method: "unlock", group: "#board", authority_level: "director" },
  answered_at: "2026-11-09T14:32:00Z",
  decision: { rule: "quorum", required: 2, eligible: 3, approvals: 0, rejections: 2, outcome: "rejected", by: ["#board"] },
  votes: [
    { identity: "ana@acme.com", handle: "ana", approve: false, answered_at: "2026-11-09T14:30:00Z", level_used: 1, group: "#board", authority_level: "director" },
    { identity: "bruno@acme.com", handle: "bruno", approve: false, answered_at: "2026-11-09T14:32:00Z", level_used: 1, group: "#board", authority_level: "director" },
  ],
  signature: { alg: "Ed25519", key_id: "k1", value: "sig" },
};

describe("decision rules", () => {
  it("sends decision and escalation in snake_case and reads the partial state", async () => {
    server = await fakeServer((req, res) =>
      sendJson(res, 201, {
        id,
        status: "open",
        version: 1,
        level: 1,
        title: "Contract 4521",
        created_at: "2026-11-09T14:00:00Z",
        decision: {
          rule: "quorum", required: 2, eligible: 3, approvals: 1, rejections: 0, pending: 2, by: ["#board"],
          votes: [{ handle: "ana", display_name: "Ana", approve: true, at: "2026-11-09T14:30:00Z", group: "#board", authority_level: "director" }],
          escalation_step: 0, escalation_steps: 1, next_escalation_at: "2026-11-09T14:30:00Z",
        },
      }),
    );
    const n = new NotificMe({ token: "nm_svc_test", baseUrl: server.url });
    const info = await n.createAsk({
      to: "#finance",
      title: "Contract 4521",
      questions,
      decision: { by: "#board>=director", rule: "quorum", required: 2, requestedBy: "@carla" },
      escalate: [{ after: "30m", to: "#ceo" }, "@cfo"],
    });
    expect(server.requests[0]!.json).toMatchObject({
      to: ["#finance"],
      decision: { by: ["#board>=director"], rule: "quorum", required: 2, requested_by: "@carla" },
      escalate: [{ after: "30m", to: ["#ceo"] }, "@cfo"],
    });
    expect(info.decision).toMatchObject({ approvals: 1, pending: 2, escalationSteps: 1 });
    expect(info.decision?.votes[0]).toEqual({ handle: "ana", displayName: "Ana", approve: true, at: "2026-11-09T14:30:00Z", group: "#board", authorityLevel: "director" });
  });

  it("maps a reply closed by the rule", () => {
    const r = toAskResult(decided, questions);
    expect(r.status).toBe("rejected");
    expect(r.decision).toEqual({ rule: "quorum", required: 2, eligible: 3, approvals: 0, rejections: 2, outcome: "rejected", by: ["#board"] });
    expect(r.votes?.map((v) => v.handle)).toEqual(["ana", "bruno"]);
    expect(r.answeredBy).toMatchObject({ group: "#board", authorityLevel: "director" });
  });
});
