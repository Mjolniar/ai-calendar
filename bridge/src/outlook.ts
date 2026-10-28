import { createHash, randomBytes } from "node:crypto";
import type { Request, Response } from "express";
import type { CalendarStore } from "./store.js";
import { PairingGuard } from "./security.js";
import { localDateOf } from "./recurrence.js";
import { homeTimeZone } from "./zone.js";

export type OutlookConfig = {
  clientId: string;
  clientSecret: string;
  /** Mails `sendMail` may send per day (the household's day, `homeTimeZone()`). Default 20. */
  dailySendLimit?: number;
};

const DEFAULT_DAILY_SEND_LIMIT = 20;
/** To and cc together. The tool schema allows 5 + 5; this holds even if the schema grows. */
const MAX_RECIPIENTS = 10;
const SEND_COUNT_KEY = "outlookSendCount";
const SEND_LOG_KEY = "outlookSendLog";
const SEND_LOG_ENTRIES = 300;

/** One line of the send log: metadata only, never the subject, body or full addresses. */
type SendLogEntry = {
  at: string;
  mode: "new" | "reply";
  outcome: "sent" | "failed" | "refused";
  recipients: number;
  domains: string[];
  subjectLength: number;
  reason?: string;
};

const AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
const GRAPH = "https://graph.microsoft.com/v1.0";
const BASE_SCOPES = "offline_access openid email User.Read Mail.ReadWrite Mail.Send";
const SCOPES = `${BASE_SCOPES} MailboxSettings.ReadWrite`;
const MAILBOX_SCOPE = "mailboxsettings.readwrite";
const ACCOUNT_KEY = "outlookAccount";
const STATE_PREFIX = "outlookState:";
const STATE_TTL_MS = 10 * 60 * 1000;
const REQUEST_TIMEOUT_MS = 20_000;
const MAX_RETRY_AFTER_MS = 5_000;

type StoredAccount = {
  refreshToken: string;
  email: string;
  connectedAt: string;
  updatedAt: string;
};

type Fetcher = typeof fetch;

export const FOLDERS = ["inbox", "junkemail", "sentitems", "archive", "drafts"] as const;
export type FolderName = (typeof FOLDERS)[number];
/** Well-known folders a message may be moved into. Deleted Items is never a destination. */
export const MOVE_DESTINATIONS = ["junkemail", "inbox", "archive"] as const;
/** Graph folder ids are base64url-like; well-known names are plain words. */
export const FOLDER_ID_PATTERN = /^[A-Za-z0-9_=+\/-]{1,512}$/;
const NEXT_LINK_PATTERN = /^https:\/\/graph\.microsoft\.com\/v1\.0\/me\/mailFolders(?:\('[A-Za-z0-9_=+\/-]{1,512}'\)|\/[A-Za-z0-9_=+-]{1,512})\/messages\?/;
const FORBIDDEN_FOLDERS = new Set(["deleteditems", "recoverableitemsdeletions", "recoverableitemspurges", "recoverableitemsroot", "outbox"]);

export type RulePredicates = {
  senderContains?: string[];
  fromAddresses?: string[];
  subjectContains?: string[];
  bodyOrSubjectContains?: string[];
};
export type RuleInput = {
  displayName: string;
  sequence?: number;
  isEnabled?: boolean;
  conditions?: RulePredicates;
  exceptions?: RulePredicates;
  moveToFolder: string;
  stopProcessingRules?: boolean;
};
type GraphRule = {
  id: string;
  displayName: string;
  sequence?: number;
  isEnabled?: boolean;
  hasError?: boolean;
  conditions?: Record<string, unknown>;
  exceptions?: Record<string, unknown>;
  actions?: Record<string, unknown>;
};
type GraphFolder = { id: string; displayName: string; parentFolderId?: string; childFolderCount?: number; totalItemCount?: number; unreadItemCount?: number };

const LIST_FIELDS = [
  "id", "internetMessageId", "conversationId", "subject", "from", "sender", "toRecipients", "ccRecipients",
  "receivedDateTime", "sentDateTime", "bodyPreview", "isRead", "hasAttachments", "parentFolderId",
  "webLink",
].join(",");

export class OutlookService {
  private cachedAccess: { token: string; expiresAt: number; scope: string } | null = null;
  private deletedItemsId: string | null = null;
  private rootFolderId: string | null = null;
  private readonly parentCache = new Map<string, { parent: string | undefined; until: number }>();
  private refreshing: Promise<string> | null = null;

  constructor(
    private readonly store: CalendarStore,
    private readonly config: OutlookConfig,
    private readonly baseUrl: string,
    private readonly pairingCode: string,
    private readonly fetcher: Fetcher = fetch,
    private readonly pairing: PairingGuard = new PairingGuard(pairingCode),
  ) {}

  get redirectUri(): string {
    return `${this.baseUrl}/outlook/callback`;
  }

  // ---------- Connect flow (one-time, user-driven) ----------

  connectPage(_req: Request, res: Response): void {
    res.type("html").send(page("Connect Outlook", `<p>Link a personal Outlook/Hotmail mailbox to the AI Calendar planner. Enter the pairing code, then sign in with Microsoft.</p><form method="post" action="${esc(this.baseUrl)}/outlook/connect"><label>Pairing code<input name="pairing_code" type="password" required></label><button type="submit">Continue to Microsoft</button></form>`));
  }

  async startConnect(req: Request, res: Response): Promise<void> {
    const pairing = await this.pairing.check(req, req.body?.pairing_code);
    if (!pairing.ok) {
      if (pairing.retryAfter) res.setHeader("Retry-After", String(pairing.retryAfter));
      res.status(pairing.status).type("text").send(pairing.status === 429 ? "Too many attempts. Try again later." : "Invalid pairing code");
      return;
    }
    const state = randomBytes(24).toString("base64url");
    const verifier = randomBytes(48).toString("base64url");
    await this.store.putKv(STATE_PREFIX + state, { verifier, expiresAt: Date.now() + STATE_TTL_MS });
    const url = new URL(`${AUTHORITY}/authorize`);
    url.searchParams.set("client_id", this.config.clientId);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("redirect_uri", this.redirectUri);
    url.searchParams.set("response_mode", "query");
    url.searchParams.set("scope", SCOPES);
    url.searchParams.set("state", state);
    url.searchParams.set("code_challenge", createHash("sha256").update(verifier).digest("base64url"));
    url.searchParams.set("code_challenge_method", "S256");
    url.searchParams.set("prompt", "select_account");
    res.redirect(303, url.toString());
  }

  async callback(req: Request, res: Response): Promise<void> {
    const state = String(req.query.state ?? "");
    const code = String(req.query.code ?? "");
    if (req.query.error) {
      res.status(400).send(page("Outlook not connected", `<p>Microsoft returned: ${esc(String(req.query.error_description ?? req.query.error))}</p>`));
      return;
    }
    const saved = state ? await this.store.takeKv(STATE_PREFIX + state) as { verifier: string; expiresAt: number } | null : null;
    if (!saved || saved.expiresAt < Date.now() || !code) {
      res.status(400).send(page("Outlook not connected", "<p>The sign-in request expired or was invalid. Start again.</p>"));
      return;
    }
    const token = await this.tokenRequest({
      grant_type: "authorization_code",
      code,
      redirect_uri: this.redirectUri,
      code_verifier: saved.verifier,
    });
    this.cachedAccess = { token: token.access_token, expiresAt: Date.now() + (token.expires_in - 120) * 1000, scope: token.scope };
    const me = await this.graph<{ mail?: string; userPrincipalName?: string }>("GET", "/me?$select=mail,userPrincipalName");
    const now = new Date().toISOString();
    await this.store.putKv(ACCOUNT_KEY, {
      refreshToken: token.refresh_token,
      email: me.mail ?? me.userPrincipalName ?? "unknown",
      connectedAt: now,
      updatedAt: now,
    } satisfies StoredAccount);
    res.send(page("Outlook connected", `<p>Connected ${esc(me.mail ?? me.userPrincipalName ?? "")}. You can close this page.</p>`));
  }

  // ---------- Token handling ----------

  private async tokenRequest(params: Record<string, string>, scope = SCOPES) {
    const body = new URLSearchParams({
      client_id: this.config.clientId,
      client_secret: this.config.clientSecret,
      scope,
      ...params,
    });
    const response = await this.fetcher(`${AUTHORITY}/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    const json = parseJson(await response.text()) as { access_token?: string; refresh_token?: string; expires_in?: number; scope?: string; error?: string; error_description?: string };
    if (!response.ok || !json.access_token) {
      throw new OutlookError(`Microsoft token request failed: ${typeof json.error === "string" ? json.error : response.status} ${(json.error_description ?? "").split("\r\n")[0]}`, "auth");
    }
    return { access_token: json.access_token, refresh_token: json.refresh_token ?? "", expires_in: json.expires_in ?? 3600, scope: json.scope ?? scope };
  }

  private async accessToken(): Promise<string> {
    if (this.cachedAccess && this.cachedAccess.expiresAt > Date.now()) return this.cachedAccess.token;
    // Concurrent tool calls share one refresh instead of racing refresh-token rotation.
    this.refreshing ??= this.refreshAccessToken().finally(() => { this.refreshing = null; });
    return this.refreshing;
  }

  private async refreshAccessToken(): Promise<string> {
    const account = await this.store.getKv(ACCOUNT_KEY) as StoredAccount | null;
    if (!account?.refreshToken) throw new OutlookError(`Outlook is not connected. Visit ${this.baseUrl}/outlook/connect`, "not_connected");
    let token: Awaited<ReturnType<OutlookService["tokenRequest"]>>;
    try {
      token = await this.tokenRequest({ grant_type: "refresh_token", refresh_token: account.refreshToken });
    } catch (error) {
      // Accounts connected before MailboxSettings.ReadWrite was added keep working on the original scopes until re-consent.
      if (!(error instanceof OutlookError) || error.kind !== "auth") throw error;
      token = await this.tokenRequest({ grant_type: "refresh_token", refresh_token: account.refreshToken }, BASE_SCOPES);
    }
    if (token.refresh_token && token.refresh_token !== account.refreshToken) {
      await this.store.putKv(ACCOUNT_KEY, { ...account, refreshToken: token.refresh_token, updatedAt: new Date().toISOString() });
    }
    this.cachedAccess = { token: token.access_token, expiresAt: Date.now() + (token.expires_in - 120) * 1000, scope: token.scope };
    return token.access_token;
  }

  private async graph<T>(method: string, pathOrUrl: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
    const url = pathOrUrl.startsWith("https://") ? pathOrUrl : `${GRAPH}${pathOrUrl}`;
    if (!url.startsWith(GRAPH + "/")) throw new OutlookError("Refusing non-Graph URL", "invalid");
    const doFetch = async (token: string) => this.fetcher(url, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    let response = await doFetch(await this.accessToken());
    if (response.status === 401) {
      this.cachedAccess = null;
      response = await doFetch(await this.accessToken());
    }
    // One bounded retry: 429 means the request was not processed; 503/504 are retried only for reads.
    if (response.status === 429 || (method === "GET" && (response.status === 503 || response.status === 504))) {
      const header = response.headers.get("retry-after");
      const seconds = header !== null && Number.isFinite(Number(header)) && Number(header) >= 0 ? Number(header) : 1;
      const retryAfterMs = Math.min(seconds * 1000, MAX_RETRY_AFTER_MS);
      await response.body?.cancel().catch(() => undefined);
      await new Promise((resolve) => setTimeout(resolve, retryAfterMs));
      response = await doFetch(await this.accessToken());
    }
    if (response.status === 202 || response.status === 204) return {} as T;
    const text = await response.text();
    const json = parseJson(text) as { error?: { code?: string; message?: string } } | T;
    if (!response.ok) {
      const error = (json as { error?: { code?: string; message?: string } })?.error;
      throw new OutlookError(`Graph ${method} failed (${response.status}): ${error?.code ?? ""} ${error?.message ?? ""}`.trim(), response.status === 429 ? "throttled" : "graph");
    }
    return json as T;
  }

  // ---------- Mail operations ----------

  async status() {
    const account = await this.store.getKv(ACCOUNT_KEY) as StoredAccount | null;
    if (!account) return { connected: false, connectUrl: `${this.baseUrl}/outlook/connect` };
    await this.accessToken();
    const mailboxSettings = this.hasMailboxScope();
    return {
      connected: true,
      email: account.email,
      connectedAt: account.connectedAt,
      mailboxSettingsGranted: mailboxSettings,
      reconsentUrl: mailboxSettings ? "" : `${this.baseUrl}/outlook/connect`,
    };
  }

  private hasMailboxScope(): boolean {
    return (this.cachedAccess?.scope ?? "").toLowerCase().split(/\s+/).some((s) => s === MAILBOX_SCOPE || s.endsWith(`/${MAILBOX_SCOPE}`));
  }

  private async requireMailboxScope(): Promise<void> {
    await this.accessToken();
    if (!this.hasMailboxScope()) {
      // Another instance may have completed re-consent; refresh once before refusing.
      this.cachedAccess = null;
      await this.accessToken();
    }
    if (!this.hasMailboxScope()) {
      throw new OutlookError(`Mail rules need MailboxSettings.ReadWrite. Re-consent at ${this.baseUrl}/outlook/connect`, "auth");
    }
  }

  // ---------- Folders ----------

  private folderRef(folder: string): string {
    if (!FOLDER_ID_PATTERN.test(folder)) throw new OutlookError("Invalid folder id", "invalid");
    if (FORBIDDEN_FOLDERS.has(folder.toLowerCase())) throw new OutlookError(`Folder ${folder} is not allowed`, "invalid");
    return encodeURIComponent(folder);
  }

  /**
   * Refuses Deleted Items and anything inside it, at any depth: mail moved there is purged when
   * Deleted Items is emptied, so it would break "never deletes". Walks the folder's parents.
   */
  private async assertNotDeletedItems(folderId: string): Promise<void> {
    if ((FOLDERS as readonly string[]).includes(folderId) || folderId === "msgfolderroot") return;
    this.deletedItemsId ??= (await this.graph<{ id: string }>("GET", "/me/mailFolders/deleteditems?$select=id")).id;
    this.rootFolderId ??= (await this.graph<{ id: string }>("GET", "/me/mailFolders/msgfolderroot?$select=id")).id;
    let id: string | undefined = folderId;
    for (let depth = 0; id && depth < 16; depth += 1) {
      if (id === this.deletedItemsId) throw new OutlookError("Deleted Items, and folders inside it, are never a destination", "invalid");
      if (id === this.rootFolderId) return;
      id = await this.parentOf(id);
    }
    // Deeper than any real mailbox, or a folder without a parent: refuse rather than guess.
    if (id) throw new OutlookError("That folder is nested too deeply to check", "invalid");
  }

  /** A folder's parent, cached for five minutes (a folder can be moved, so not for longer). */
  private async parentOf(folderId: string): Promise<string | undefined> {
    const cached = this.parentCache.get(folderId);
    if (cached && cached.until > Date.now()) return cached.parent;
    const folder = await this.graph<GraphFolder>("GET", `/me/mailFolders/${encodeURIComponent(folderId)}?$select=id,parentFolderId`);
    const parent = folder.parentFolderId && folder.parentFolderId !== folderId ? folder.parentFolderId : undefined;
    if (this.parentCache.size > 500) this.parentCache.clear();
    this.parentCache.set(folderId, { parent, until: Date.now() + 5 * 60 * 1000 });
    return parent;
  }

  async listFolders(parent = "inbox") {
    const folders: GraphFolder[] = [];
    let url: string | undefined = `/me/mailFolders/${this.folderRef(parent)}/childFolders?$select=id,displayName,parentFolderId,childFolderCount,totalItemCount,unreadItemCount&$top=100`;
    for (let i = 0; url && i < 10; i += 1) {
      const page: { value: GraphFolder[]; "@odata.nextLink"?: string } = await this.graph("GET", url);
      folders.push(...page.value);
      url = page["@odata.nextLink"];
    }
    return {
      parent,
      folders: folders.map((f) => ({
        id: f.id, displayName: f.displayName, parentFolderId: f.parentFolderId ?? "",
        childFolderCount: f.childFolderCount ?? 0, totalItemCount: f.totalItemCount ?? 0, unreadItemCount: f.unreadItemCount ?? 0,
      })),
    };
  }

  async createFolder(displayName: string, parent = "inbox") {
    this.folderRef(parent);
    await this.assertNotDeletedItems(parent);
    const name = displayName.trim();
    const find = async () => (await this.listFolders(parent)).folders.find((f) => f.displayName.toLowerCase() === name.toLowerCase());
    const existing = await find();
    if (existing) return { created: false, folder: existing };
    try {
      const f = await this.graph<GraphFolder>("POST", `/me/mailFolders/${this.folderRef(parent)}/childFolders`, { displayName: name, isHidden: false });
      return { created: true, folder: { id: f.id, displayName: f.displayName, parentFolderId: f.parentFolderId ?? "", childFolderCount: 0, totalItemCount: 0, unreadItemCount: 0 } };
    } catch (error) {
      // A concurrent create returns 409 ErrorFolderExists; resolve to the existing folder.
      const again = await find();
      if (again) return { created: false, folder: again };
      throw error;
    }
  }

  /**
   * Delete a custom folder only when it is empty (no messages, no child folders) and its name matches.
   * Well-known folders are refused. Graph moves the deleted folder to Deleted Items.
   */
  async deleteEmptyFolder(id: string, expectedName: string) {
    this.folderRef(id);
    if ((FOLDERS as readonly string[]).includes(id.toLowerCase()) || /^[a-z]+$/.test(id)) throw new OutlookError("Well-known folders cannot be deleted", "invalid");
    const f = await this.graph<GraphFolder>("GET", `/me/mailFolders/${encodeURIComponent(id)}?$select=id,displayName,parentFolderId,childFolderCount,totalItemCount`);
    if (f.displayName !== expectedName) throw new OutlookError(`Folder name mismatch: found "${f.displayName}"`, "invalid");
    if ((f.totalItemCount ?? 0) > 0 || (f.childFolderCount ?? 0) > 0) {
      throw new OutlookError(`Folder "${f.displayName}" is not empty (${f.totalItemCount ?? 0} messages, ${f.childFolderCount ?? 0} subfolders)`, "invalid");
    }
    const protectedIds = await this.wellKnownIds();
    if (protectedIds.has(f.id)) throw new OutlookError("Well-known folders cannot be deleted", "invalid");
    await this.graph("DELETE", `/me/mailFolders/${encodeURIComponent(f.id)}`);
    return { deleted: true, id: f.id, displayName: f.displayName };
  }

  private wellKnown: Set<string> | null = null;
  private async wellKnownIds(): Promise<Set<string>> {
    if (this.wellKnown) return this.wellKnown;
    const names = ["msgfolderroot", "inbox", "sentitems", "drafts", "deleteditems", "junkemail", "archive", "outbox"];
    const ids = new Set<string>();
    for (const n of names) {
      try { ids.add((await this.graph<{ id: string }>("GET", `/me/mailFolders/${n}?$select=id`)).id); } catch { /* archive may not exist */ }
    }
    this.wellKnown = ids;
    return ids;
  }

  // ---------- Inbox rules (moveToFolder only) ----------

  async listRules() {
    await this.requireMailboxScope();
    const page = await this.graph<{ value: GraphRule[] }>("GET", "/me/mailFolders/inbox/messageRules");
    return { rules: page.value.map(slimRule) };
  }

  private async ruleBody(input: Partial<RuleInput>) {
    const body: Record<string, unknown> = {};
    if (input.displayName !== undefined) body.displayName = input.displayName.trim();
    if (input.sequence !== undefined) body.sequence = input.sequence;
    if (input.isEnabled !== undefined) body.isEnabled = input.isEnabled;
    if (input.conditions !== undefined) body.conditions = cleanPredicates(input.conditions);
    if (input.exceptions !== undefined) body.exceptions = cleanPredicates(input.exceptions);
    if (input.moveToFolder !== undefined || input.stopProcessingRules !== undefined) {
      if (input.moveToFolder === undefined) throw new OutlookError("moveToFolder is required when changing actions", "invalid");
      this.folderRef(input.moveToFolder);
      await this.assertNotDeletedItems(input.moveToFolder);
      body.actions = { moveToFolder: input.moveToFolder, stopProcessingRules: input.stopProcessingRules ?? true };
    }
    return body;
  }

  async createRule(input: RuleInput) {
    await this.requireMailboxScope();
    const name = input.displayName.trim();
    const existing = (await this.listRules()).rules.find((r) => r.displayName.toLowerCase() === name.toLowerCase());
    if (existing) return { created: false, rule: existing };
    const body = await this.ruleBody({ ...input, isEnabled: input.isEnabled ?? true, stopProcessingRules: input.stopProcessingRules ?? true });
    if (!body.conditions || Object.keys(body.conditions as object).length === 0) throw new OutlookError("A rule needs at least one condition", "invalid");
    body.sequence ??= 1;
    const rule = await this.graph<GraphRule>("POST", "/me/mailFolders/inbox/messageRules", body);
    return { created: true, rule: slimRule(rule) };
  }

  async updateRule(id: string, patch: Partial<RuleInput>) {
    await this.requireMailboxScope();
    const current = (await this.listRules()).rules.find((r) => r.id === id);
    if (!current) throw new OutlookError(`Rule ${id} not found`, "invalid");
    if (current.hasOtherActions) throw new OutlookError("Refusing to edit a rule that has actions other than moveToFolder", "invalid");
    const body = await this.ruleBody({ ...patch, moveToFolder: patch.moveToFolder ?? (patch.stopProcessingRules !== undefined ? current.moveToFolder || undefined : undefined) });
    if (Object.keys(body).length === 0) return { updated: false, rule: current };
    const rule = await this.graph<GraphRule>("PATCH", `/me/mailFolders/inbox/messageRules/${encodeURIComponent(id)}`, body);
    return { updated: true, rule: slimRule(rule) };
  }

  async listMessages(input: { folder?: string; receivedAfter?: string; receivedBefore?: string; top?: number; nextLink?: string; unreadOnly?: boolean }) {
    let url: string;
    if (input.nextLink) {
      // Continuation links may only page through mail folders, never reach other Graph resources.
      // Graph returns links shaped like /me/mailFolders('inbox')/messages?$skiptoken=... (or /mailFolders/{id}/messages).
      if (!NEXT_LINK_PATTERN.test(input.nextLink)) throw new OutlookError("Refusing non-Graph URL", "invalid");
      url = input.nextLink;
    } else {
      const filters: string[] = [];
      if (input.receivedAfter) filters.push(`receivedDateTime ge ${new Date(input.receivedAfter).toISOString()}`);
      if (input.receivedBefore) filters.push(`receivedDateTime lt ${new Date(input.receivedBefore).toISOString()}`);
      if (input.unreadOnly) filters.push("isRead eq false");
      const params = new URLSearchParams({
        $select: LIST_FIELDS,
        $orderby: "receivedDateTime desc",
        $top: String(Math.min(Math.max(input.top ?? 25, 1), 50)),
      });
      if (filters.length) params.set("$filter", filters.join(" and "));
      url = `${GRAPH}/me/mailFolders/${this.folderRef(input.folder ?? "inbox")}/messages?${params.toString().replace(/\+/g, "%20")}`;
    }
    const page = await this.graph<{ value: GraphMessage[]; "@odata.nextLink"?: string }>("GET", url);
    return {
      messages: page.value.map(slimMessage),
      nextLink: page["@odata.nextLink"] ?? null,
      complete: !page["@odata.nextLink"],
    };
  }

  async getMessage(id: string, maxBodyChars = 8000) {
    const fields = `${LIST_FIELDS},body,replyTo,internetMessageHeaders`;
    const message = await this.graph<GraphMessage & {
      body?: { content?: string };
      replyTo?: Recipient[];
      internetMessageHeaders?: Array<{ name: string; value: string }>;
    }>("GET", `/me/messages/${encodeURIComponent(id)}?$select=${fields}`, undefined, { Prefer: 'outlook.body-content-type="text"' });
    let attachments: Array<{ name: string; contentType: string; size: number }> = [];
    if (message.hasAttachments) {
      const list = await this.graph<{ value: Array<{ name: string; contentType: string; size: number }> }>(
        "GET", `/me/messages/${encodeURIComponent(id)}/attachments?$select=name,contentType,size`);
      attachments = list.value.map(({ name, contentType, size }) => ({ name, contentType, size }));
    }
    const wanted = new Set(["list-unsubscribe", "authentication-results", "return-path", "reply-to"]);
    const bodyText = (message.body?.content ?? "").replace(/\r/g, "").replace(/\n{3,}/g, "\n\n").trim();
    return {
      ...slimMessage(message),
      replyTo: (message.replyTo ?? []).map(addr),
      headers: (message.internetMessageHeaders ?? []).filter((h) => wanted.has(h.name.toLowerCase())).map((h) => ({ name: h.name, value: h.value.slice(0, 600) })),
      unsubscribeMailto: listUnsubscribeMailto(message.internetMessageHeaders),
      body: bodyText.slice(0, Math.min(Math.max(maxBodyChars, 500), 30000)),
      bodyTruncated: bodyText.length > maxBodyChars,
      attachments,
    };
  }

  /**
   * Sends one mail. Guard rails (audit M1): at most 10 recipients, at most `dailySendLimit` sends a
   * day across all instances (a Firestore counter, taken in a transaction before sending and given
   * back if Microsoft refuses), and a send log with metadata only.
   */
  async sendMail(input: { to: string[]; cc?: string[]; subject: string; body: string; replyToMessageId?: string }) {
    const mode: SendLogEntry["mode"] = input.replyToMessageId ? "reply" : "new";
    const addresses = [...input.to, ...(input.cc ?? [])];
    const meta = { mode, recipients: addresses.length, domains: recipientDomains(addresses), subjectLength: input.subject.length };
    if (addresses.length > MAX_RECIPIENTS) {
      await this.logSend({ ...meta, outcome: "refused", reason: "recipients" });
      throw new OutlookError(`At most ${MAX_RECIPIENTS} recipients (to and cc together) per mail`, "invalid");
    }
    const limit = this.config.dailySendLimit ?? DEFAULT_DAILY_SEND_LIMIT;
    const zone = homeTimeZone();
    const day = localDateOf(Date.now(), zone);
    const reserved = await this.store.mutateKv(SEND_COUNT_KEY, (current) => {
      const count = current?.day === day && typeof current.count === "number" ? current.count : 0;
      return count >= limit ? { value: null, result: false } : { value: { day, count: count + 1 }, result: true };
    });
    if (!reserved) {
      await this.logSend({ ...meta, outcome: "refused", reason: "daily limit" });
      throw new OutlookError(`Daily send limit reached: at most ${limit} mails per day (${zone}). Nothing was sent; ask the user to send it themselves or wait until tomorrow.`, "invalid");
    }
    const toRecipients = input.to.map((address) => ({ emailAddress: { address } }));
    const ccRecipients = (input.cc ?? []).map((address) => ({ emailAddress: { address } }));
    try {
      if (input.replyToMessageId) {
        await this.graph("POST", `/me/messages/${encodeURIComponent(input.replyToMessageId)}/reply`, {
          message: { toRecipients, ccRecipients },
          comment: input.body,
        });
      } else {
        await this.graph("POST", "/me/sendMail", {
          message: { subject: input.subject, body: { contentType: "Text", content: input.body }, toRecipients, ccRecipients },
          saveToSentItems: true,
        });
      }
    } catch (error) {
      // Not sent: give the reservation back, so a refused send does not use up the day.
      await this.store.mutateKv(SEND_COUNT_KEY, (current) => current?.day === day && typeof current.count === "number" && current.count > 0
        ? { value: { day, count: current.count - 1 }, result: undefined }
        : { value: null, result: undefined }).catch(() => undefined);
      await this.logSend({ ...meta, outcome: "failed" });
      throw error;
    }
    await this.logSend({ ...meta, outcome: "sent" });
    return { sent: true, mode, sentAt: new Date().toISOString() };
  }

  /** Appends to the send log (last 300 entries). Best effort: the log never blocks or fails a send. */
  private async logSend(entry: Omit<SendLogEntry, "at">): Promise<void> {
    const line: SendLogEntry = { at: new Date().toISOString(), ...entry };
    console.log("Outlook send", line);
    try {
      await this.store.mutateKv(SEND_LOG_KEY, (current) => {
        const entries = Array.isArray(current?.entries) ? current.entries as SendLogEntry[] : [];
        return { value: { entries: [...entries, line].slice(-SEND_LOG_ENTRIES) }, result: undefined };
      });
    } catch (error) {
      console.warn("Could not write the Outlook send log", error instanceof Error ? error.message : error);
    }
  }

  async moveMessage(id: string, destination: string) {
    this.folderRef(destination);
    await this.assertNotDeletedItems(destination);
    const moved = await this.graph<GraphMessage>("POST", `/me/messages/${encodeURIComponent(id)}/move`, { destinationId: destination });
    return { moved: true, newId: moved.id, destination };
  }

  /** Move up to 20 messages in one Graph $batch. Never deletes; Deleted Items is refused. */
  async moveMessages(ids: string[], destination: string) {
    this.folderRef(destination);
    await this.assertNotDeletedItems(destination);
    const unique = [...new Set(ids)].slice(0, 20);
    const requests = unique.map((id, i) => ({
      id: String(i),
      method: "POST",
      url: `/me/messages/${encodeURIComponent(id)}/move`,
      headers: { "Content-Type": "application/json" },
      body: { destinationId: destination },
    }));
    const res = await this.graph<{ responses?: Array<{ id: string; status: number; body?: { id?: string; error?: { code?: string; message?: string } } }> }>("POST", "/$batch", { requests });
    const byId = new Map((res.responses ?? []).map((r) => [r.id, r]));
    const results = unique.map((id, i) => {
      const r = byId.get(String(i));
      const ok = Boolean(r && r.status >= 200 && r.status < 300);
      return ok
        ? { id, ok: true, newId: r?.body?.id ?? "" }
        : { id, ok: false, status: r?.status ?? 0, error: `${r?.body?.error?.code ?? "no_response"} ${r?.body?.error?.message ?? ""}`.trim().slice(0, 200) };
    });
    return { destination, moved: results.filter((r) => r.ok).length, failed: results.filter((r) => !r.ok).length, results };
  }

  async unsubscribe(id: string) {
    const message = await this.graph<{ internetMessageHeaders?: Array<{ name: string; value: string }> }>(
      "GET", `/me/messages/${encodeURIComponent(id)}?$select=internetMessageHeaders`);
    const mailto = listUnsubscribeMailto(message.internetMessageHeaders);
    if (mailto.length === 0) {
      return { unsubscribed: false, reason: "Message has no mailto List-Unsubscribe header" };
    }
    const target = new URL(mailto[0]);
    const address = decodeURIComponent(target.pathname);
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(address)) {
      return { unsubscribed: false, reason: "List-Unsubscribe mailto address is malformed" };
    }
    // The sender wrote this header: send only an unsubscribe request, never their text. A list's
    // own subject is kept when it is a token or says unsubscribe, which some lists require.
    const requested = (target.searchParams.get("subject") ?? "").trim();
    const subject = /^[\w.:@#+=-]{1,80}$/.test(requested)
      || (/\b(unsubscribe|unsub|remove|opt[\s-]?out|leave)\b/i.test(requested) && requested.length <= 80 && !/[\r\n]|https?:|www\./i.test(requested))
      ? requested
      : "unsubscribe";
    const body = "unsubscribe";
    await this.graph("POST", "/me/sendMail", {
      message: { subject, body: { contentType: "Text", content: body }, toRecipients: [{ emailAddress: { address } }] },
      saveToSentItems: true,
    });
    return { unsubscribed: true, via: `mailto:${address}` };
  }
}

const PREDICATE_KEYS = ["senderContains", "fromAddresses", "subjectContains", "bodyOrSubjectContains"] as const;

function cleanPredicates(p: RulePredicates): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const key of PREDICATE_KEYS) {
    const values = [...new Set((p[key] ?? []).map((v) => v.trim()).filter(Boolean))];
    if (values.length === 0) continue;
    out[key] = key === "fromAddresses" ? values.map((address) => ({ emailAddress: { address } })) : values;
  }
  return out;
}

function slimPredicates(p?: Record<string, unknown>): RulePredicates {
  const out: RulePredicates = {};
  if (!p) return out;
  for (const key of PREDICATE_KEYS) {
    const v = p[key];
    if (!Array.isArray(v) || v.length === 0) continue;
    out[key] = key === "fromAddresses"
      ? (v as Recipient[]).map((r) => r.emailAddress?.address ?? "")
      : (v as string[]);
  }
  return out;
}

function slimRule(r: GraphRule) {
  const actions = r.actions ?? {};
  const other = Object.entries(actions).filter(([k, v]) => !["moveToFolder", "stopProcessingRules"].includes(k) && v !== null && v !== false && !(Array.isArray(v) && v.length === 0));
  return {
    id: r.id,
    displayName: r.displayName,
    sequence: r.sequence ?? 0,
    isEnabled: Boolean(r.isEnabled),
    hasError: Boolean(r.hasError),
    conditions: slimPredicates(r.conditions),
    exceptions: slimPredicates(r.exceptions),
    moveToFolder: typeof actions.moveToFolder === "string" ? actions.moveToFolder : "",
    stopProcessingRules: Boolean(actions.stopProcessingRules),
    hasOtherActions: other.length > 0,
    otherActions: other.map(([k]) => k),
  };
}

function parseJson(text: string): unknown {
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { error: { code: "invalid_response", message: text.slice(0, 200) } };
  }
}

/** The distinct domains of the addresses, lower case, sorted. Never the part before the @. */
function recipientDomains(addresses: string[]): string[] {
  return [...new Set(addresses.map((address) => address.split("@").pop()?.trim().toLowerCase() ?? "").filter(Boolean))].sort();
}

function listUnsubscribeMailto(headers?: Array<{ name: string; value: string }>): string[] {
  const header = (headers ?? []).find((h) => h.name.toLowerCase() === "list-unsubscribe")?.value ?? "";
  return [...header.matchAll(/<\s*(mailto:[^>\s]+)\s*>/gi)].map((m) => m[1]);
}

export class OutlookError extends Error {
  constructor(message: string, readonly kind: "auth" | "not_connected" | "graph" | "throttled" | "invalid") {
    super(message);
  }
}

type Recipient = { emailAddress?: { name?: string; address?: string } };
type GraphMessage = {
  id: string;
  internetMessageId?: string;
  conversationId?: string;
  subject?: string;
  from?: Recipient;
  sender?: Recipient;
  toRecipients?: Recipient[];
  ccRecipients?: Recipient[];
  receivedDateTime?: string;
  sentDateTime?: string;
  bodyPreview?: string;
  isRead?: boolean;
  hasAttachments?: boolean;
  parentFolderId?: string;
  webLink?: string;
};

function addr(r?: Recipient) {
  return { name: r?.emailAddress?.name ?? "", address: r?.emailAddress?.address ?? "" };
}

function slimMessage(m: GraphMessage) {
  return {
    id: m.id,
    internetMessageId: m.internetMessageId ?? "",
    conversationId: m.conversationId ?? "",
    subject: m.subject ?? "",
    from: addr(m.from),
    sender: addr(m.sender),
    to: (m.toRecipients ?? []).map(addr),
    cc: (m.ccRecipients ?? []).map(addr),
    receivedDateTime: m.receivedDateTime ?? "",
    sentDateTime: m.sentDateTime ?? "",
    preview: (m.bodyPreview ?? "").slice(0, 300),
    isRead: Boolean(m.isRead),
    hasAttachments: Boolean(m.hasAttachments),
  };
}

function esc(value: string): string {
  return value.replace(/[&<>'"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[c] ?? c);
}

function page(title: string, inner: string): string {
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>${esc(title)}</title><style>body{font-family:system-ui;max-width:420px;margin:48px auto;padding:20px;color:#1f2937}input,button{box-sizing:border-box;width:100%;padding:12px;margin-top:12px;font:inherit}button{background:#176b3a;color:white;border:0;border-radius:6px}p{line-height:1.5}</style></head><body><h1>${esc(title)}</h1>${inner}</body></html>`;
}
