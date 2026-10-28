import request from "supertest";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { NoopNotifier } from "../src/notifier.js";
import { MemoryCalendarStore } from "../src/store.js";

/**
 * Mailbox: Deleted Items (DEL) holds a subfolder SUB, which holds DEEP. Bills sits under the Inbox.
 * The unsubscribe header can be set per test.
 */
function mailbox(unsubscribeHeader = "<mailto:unsub@list.example.com?subject=unsubscribe>") {
  const sent: Array<{ subject: string; body: string; to: string[] }> = [];
  const parents: Record<string, string> = { DEL: "ROOT", SUB: "DEL", DEEP: "SUB", BILLS: "WK-inbox", "WK-inbox": "ROOT" };
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = decodeURIComponent(String(input));
    const method = init?.method ?? "GET";
    if (url.endsWith("/oauth2/v2.0/token")) return json({ access_token: "at", refresh_token: "rt2", expires_in: 3600, scope: "Mail.ReadWrite Mail.Send User.Read MailboxSettings.ReadWrite" });
    if (url.includes("/mailFolders/deleteditems?")) return json({ id: "DEL" });
    if (url.includes("/mailFolders/msgfolderroot?")) return json({ id: "ROOT" });
    const wellKnown = url.match(/\/mailFolders\/(inbox|sentitems|drafts|junkemail|archive|outbox)\?\$select=id$/);
    if (wellKnown) return json({ id: `WK-${wellKnown[1]}` });
    const one = url.match(/\/mailFolders\/([^/?]+)\?\$select=/);
    if (one && method === "GET") {
      const parent = parents[one[1]];
      return parent ? json({ id: one[1], displayName: one[1], parentFolderId: parent, childFolderCount: 0, totalItemCount: 0 }) : json({ error: { code: "ErrorItemNotFound", message: "x" } }, 404);
    }
    if (url.includes("/childFolders") && method === "GET") return json({ value: [] });
    if (url.includes("/childFolders") && method === "POST") return json({ id: "NEW==", displayName: "x", parentFolderId: "?" }, 201);
    if (url.endsWith("/mailFolders/inbox/messageRules") && method === "GET") return json({ value: [] });
    if (url.endsWith("/mailFolders/inbox/messageRules") && method === "POST") return json({ id: "R1", ...(JSON.parse(String(init?.body)) as object) }, 201);
    if (url.includes("$select=internetMessageHeaders")) return json({ internetMessageHeaders: [{ name: "List-Unsubscribe", value: unsubscribeHeader }] });
    if (url.endsWith("/me/sendMail")) {
      const message = (JSON.parse(String(init?.body)) as { message: { subject: string; body: { content: string }; toRecipients: Array<{ emailAddress: { address: string } }> } }).message;
      sent.push({ subject: message.subject, body: message.body.content, to: message.toRecipients.map((r) => r.emailAddress.address) });
      return new Response(null, { status: 202 });
    }
    if (url.includes("/move")) return json({ id: "moved" });
    if (url.endsWith("/$batch")) return json({ responses: [] });
    return json({ error: { code: "NotFound", message: url } }, 404);
  }) as typeof fetch;
  return { fetcher, sent };
}

async function setup(header?: string) {
  const store = new MemoryCalendarStore();
  await store.putKv("outlookAccount", { refreshToken: "rt", email: "user@example.com", connectedAt: "", updatedAt: "" });
  const box = mailbox(header);
  const app = createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: "654321",
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: "https://calendar.example.test/api",
    outlook: { clientId: "c", clientSecret: "s" },
    fetcher: box.fetcher,
  });
  const call = async (name: string, args: unknown) => {
    const res = await request(app).post("/mcp")
      .set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } });
    const line = res.text.split("\n").find((l) => l.startsWith("data: "));
    return JSON.parse(line ? line.slice(6) : res.text).result as { isError?: boolean; content: Array<{ text: string }>; structuredContent?: Record<string, unknown> };
  };
  return { call, sent: box.sent };
}

describe("mail never goes into Deleted Items, however deep", () => {
  it.each(["SUB==", "DEEP=="].map((id) => id.replace("==", "")))("refuses a move into %s, a folder inside Deleted Items", async (folder) => {
    const { call } = await setup();
    expect((await call("outlook_move_message", { id: "m1", destination: folder })).isError).toBe(true);
    expect((await call("outlook_move_messages", { ids: ["m1"], destination: folder })).isError).toBe(true);
  });

  it("refuses an Inbox rule or a new folder that would land inside Deleted Items", async () => {
    const { call } = await setup();
    expect((await call("outlook_create_rule", { displayName: "Bin it", moveToFolder: "SUB", conditions: { senderContains: ["news@example.com"] } })).isError).toBe(true);
    expect((await call("outlook_create_folder", { displayName: "Hidden", parent: "SUB" })).isError).toBe(true);
  });

  it("still allows ordinary folders", async () => {
    const { call } = await setup();
    expect((await call("outlook_move_message", { id: "m1", destination: "BILLS" })).isError).toBeFalsy();
    expect((await call("outlook_create_rule", { displayName: "Bills", moveToFolder: "BILLS", conditions: { senderContains: ["billing@example.com"] } })).isError).toBeFalsy();
  });
});

describe("unsubscribe sends only an unsubscribe request", () => {
  it("ignores a body and a free-text subject written by the sender", async () => {
    const { call, sent } = await setup("<mailto:someone@victim.example?subject=Invoice%20overdue%20see%20http%3A%2F%2Fevil.example&body=Please%20pay%20now>");
    expect((await call("outlook_unsubscribe", { id: "m1" })).structuredContent?.unsubscribed).toBe(true);
    expect(sent).toEqual([{ subject: "unsubscribe", body: "unsubscribe", to: ["someone@victim.example"] }]);
  });

  it("keeps a list's own unsubscribe subject or token", async () => {
    const token = await setup("<mailto:leave-123@lists.example.com?subject=unsub-8f3a9c>");
    await token.call("outlook_unsubscribe", { id: "m1" });
    expect(token.sent[0].subject).toBe("unsub-8f3a9c");
    const words = await setup("<mailto:list@example.com?subject=Unsubscribe%20me>");
    await words.call("outlook_unsubscribe", { id: "m1" });
    expect(words.sent[0]).toMatchObject({ subject: "Unsubscribe me", body: "unsubscribe" });
  });
});
