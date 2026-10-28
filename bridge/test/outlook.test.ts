import request from "supertest";
import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { NoopNotifier } from "../src/notifier.js";
import { MemoryCalendarStore } from "../src/store.js";

const BASE = "https://calendar.example.test/api";

function fakeFetch(log: string[]) {
  let refreshCount = 0;
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    log.push(`${init?.method ?? "GET"} ${url}`);
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    if (url.endsWith("/oauth2/v2.0/token")) {
      const body = new URLSearchParams(String(init?.body));
      if (body.get("grant_type") === "authorization_code") return json({ access_token: "at1", refresh_token: "rt1", expires_in: 3600 });
      refreshCount += 1;
      return json({ access_token: `at-r${refreshCount}`, refresh_token: `rt-r${refreshCount}`, expires_in: 3600 });
    }
    if (url.includes("/me?$select")) return json({ mail: "user@example.com" });
    if (url.includes("skiptoken")) return json({ value: [] });
    if (url.includes("/mailFolders/inbox/messages")) {
      return json({ value: [{ id: "m1", subject: "Quiz moved", from: { emailAddress: { address: "prof@example.edu" } }, receivedDateTime: "2026-09-25T10:00:00Z", bodyPreview: "x" }], "@odata.nextLink": "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?$skiptoken=abc" });
    }
    if (url.includes("$select=internetMessageHeaders")) return json({ internetMessageHeaders: [{ name: "List-Unsubscribe", value: "<https://x.example/u>, <mailto:unsub@list.example.com?subject=remove%20me>" }] });
    if (url.endsWith("/me/sendMail")) { log.push(`BODY ${String(init?.body)}`); return new Response(null, { status: 202 }); }
    if (url.includes("/move")) return json({ id: "m1-moved" });
    return json({ error: { code: "NotFound", message: url } }, 404);
  }) as typeof fetch;
}

function app(store: MemoryCalendarStore, log: string[]) {
  return createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: "654321",
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: BASE,
    outlook: { clientId: "client-1", clientSecret: "secret-1" },
    fetcher: fakeFetch(log),
  });
}

async function mcp(a: ReturnType<typeof app>, method: string, params: unknown) {
  const res = await request(a).post("/mcp")
    .set("Authorization", "Bearer test-api-token")
    .set("Accept", "application/json, text/event-stream")
    .send({ jsonrpc: "2.0", id: 1, method, params });
  const text = res.text;
  const line = text.split("\n").find((l) => l.startsWith("data: "));
  return JSON.parse(line ? line.slice(6) : text);
}

describe("Outlook integration", () => {
  it("connects with pairing code, stores refresh token, and lists with pagination", async () => {
    const store = new MemoryCalendarStore();
    const log: string[] = [];
    const a = app(store, log);
    await request(a).post("/outlook/connect").type("form").send({ pairing_code: "bad" }).expect(401);
    const start = await request(a).post("/outlook/connect").type("form").send({ pairing_code: "654321" }).expect(303);
    const location = new URL(start.headers.location);
    expect(location.origin + location.pathname).toBe("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize");
    expect(location.searchParams.get("redirect_uri")).toBe(`${BASE}/outlook/callback`);
    const state = location.searchParams.get("state")!;
    await request(a).get(`/outlook/callback?code=abc&state=${state}`).expect(200);
    await request(a).get(`/outlook/callback?code=abc&state=${state}`).expect(400);
    expect((await store.getKv("outlookAccount"))?.email).toBe("user@example.com");

    const tools = await mcp(a, "tools/list", {});
    const names = tools.result.tools.map((t: { name: string }) => t.name);
    expect(names).toEqual(expect.arrayContaining(["outlook_list_messages", "outlook_send_mail", "outlook_unsubscribe", "publish_task_reminders"]));

    const list = await mcp(a, "tools/call", { name: "outlook_list_messages", arguments: { receivedAfter: "2026-09-24T00:00:00Z" } });
    expect(list.result.structuredContent.complete).toBe(false);
    expect(list.result.structuredContent.messages[0].from.address).toBe("prof@example.edu");
    const next = await mcp(a, "tools/call", { name: "outlook_list_messages", arguments: { nextLink: list.result.structuredContent.nextLink } });
    expect(next.result.structuredContent.complete).toBe(true);
    expect(log.some((l) => decodeURIComponent(l).includes("receivedDateTime ge 2026-09-24T00:00:00.000Z"))).toBe(true);

    const sent = await mcp(a, "tools/call", { name: "outlook_send_mail", arguments: { to: ["office@example.edu"], subject: "Q", body: "What time?" } });
    expect(sent.result.structuredContent.sent).toBe(true);
    const moved = await mcp(a, "tools/call", { name: "outlook_move_message", arguments: { id: "m1", destination: "junkemail" } });
    expect(moved.result.structuredContent.newId).toBe("m1-moved");
    const unsub = await mcp(a, "tools/call", { name: "outlook_unsubscribe", arguments: { id: "m1" } });
    expect(unsub.result.structuredContent).toEqual({ unsubscribed: true, via: "mailto:unsub@list.example.com" });
    expect(log.some((l) => l.includes("BODY") && l.includes("remove me") && l.includes("unsub@list.example.com"))).toBe(true);
  });

  it("reports not-connected as a tool error, not a crash", async () => {
    const a = app(new MemoryCalendarStore(), []);
    const res = await mcp(a, "tools/call", { name: "outlook_list_messages", arguments: {} });
    expect(res.result.isError).toBe(true);
    expect(res.result.content[0].text).toContain("not_connected");
  });

  it("refuses non-Graph continuation links", async () => {
    const store = new MemoryCalendarStore();
    await store.putKv("outlookAccount", { refreshToken: "rt", email: "x", connectedAt: "", updatedAt: "" });
    const a = app(store, []);
    const res = await mcp(a, "tools/call", { name: "outlook_list_messages", arguments: { nextLink: "https://evil.example/steal" } });
    expect(res.result.isError).toBe(true);
  });
});

describe("OAuth leniency for Claude connectors", () => {
  it("accepts authorize/token without explicit resource or scope", async () => {
    const store = new MemoryCalendarStore();
    const a = app(store, []);
    const reg = await request(a).post("/oauth/register").send({ client_name: "Claude", redirect_uris: ["https://claude.ai/api/mcp/auth_callback"] }).expect(201);
    const verifier = "v".repeat(50);
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    const auth = await request(a).post("/oauth/authorize").type("form").send({
      response_type: "code", client_id: reg.body.client_id, redirect_uri: "https://claude.ai/api/mcp/auth_callback",
      code_challenge: challenge, code_challenge_method: "S256", state: "s1", pairing_code: "654321",
    }).expect(303);
    const code = new URL(auth.headers.location).searchParams.get("code")!;
    const tok = await request(a).post("/oauth/token").type("form").send({
      grant_type: "authorization_code", code, code_verifier: verifier, client_id: reg.body.client_id, redirect_uri: "https://claude.ai/api/mcp/auth_callback",
    }).expect(200);
    await request(a).post("/mcp").set("Authorization", `Bearer ${tok.body.access_token}`)
      .set("Accept", "application/json, text/event-stream")
      .send({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} }).expect(200);
    const refreshed = await request(a).post("/oauth/token").type("form").send({ grant_type: "refresh_token", refresh_token: tok.body.refresh_token, client_id: reg.body.client_id }).expect(200);
    expect(refreshed.body.access_token).toBeTruthy();
  });
});

describe("planner state tools", () => {
  it("replaces, appends, reads and lists", async () => {
    const a = app(new MemoryCalendarStore(), []);
    await mcp(a, "tools/call", { name: "planner_state_put", arguments: { key: "state.json", content: "{\"a\":1}" } });
    await mcp(a, "tools/call", { name: "planner_state_put", arguments: { key: "filtered-items.log", content: "line1" } });
    await mcp(a, "tools/call", { name: "planner_state_put", arguments: { key: "filtered-items.log", content: "line2", mode: "append" } });
    const log = await mcp(a, "tools/call", { name: "planner_state_get", arguments: { key: "filtered-items.log" } });
    expect(log.result.structuredContent.content).toBe("line1\nline2");
    const missing = await mcp(a, "tools/call", { name: "planner_state_get", arguments: { key: "nope.json" } });
    expect(missing.result.structuredContent.exists).toBe(false);
    const list = await mcp(a, "tools/call", { name: "planner_state_list", arguments: {} });
    expect(list.result.structuredContent.files.map((f: { key: string }) => f.key)).toEqual(["filtered-items.log", "state.json"]);
    const bad = await mcp(a, "tools/call", { name: "planner_state_get", arguments: { key: "../etc" } });
    expect(bad.result.isError).toBe(true);
  });
});
