import { createHash, randomBytes } from "node:crypto";
import { createReadStream, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import express, { type NextFunction, type Request, type Response } from "express";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { CalendarStore } from "./store.js";
import type { DeviceNotifier } from "./notifier.js";
import { CalendarService, InputError, isValidTimeZone } from "./service.js";
import { createMcpServer } from "./mcp.js";
import { AttemptLimiter, bearerToken, clientKey, hashToken, PairingGuard, secureEqual, type PairingCheck } from "./security.js";
import { OAuthService } from "./oauth.js";
import { CanvasSyncService, type CanvasConfig } from "./canvas.js";
import { OutlookService, type OutlookConfig } from "./outlook.js";
import type { EventCategory, EventDomain } from "./types.js";
import { KitchenService, type NotePatch, type PantryStatus } from "./kitchen.js";

export type AppConfig = {
  apiToken: string;
  pairingCode: string;
  firebaseClientConfig: {
    projectId: string;
    appId: string;
    apiKey: string;
    senderId: string;
  };
  publicBaseUrl: string;
  canvas?: CanvasConfig;
  outlook?: OutlookConfig;
  fetcher?: typeof fetch;
  /** Folder with the published phone build (`app.apk` and `release.json`). Default: `release/` beside the code. */
  releaseDir?: string;
};

/** The phone build the bridge offers, as `scripts/publish-app.ps1` writes it. */
export type AppRelease = {
  versionCode: number;
  versionName: string;
  sha256: string;
  size: number;
  publishedAt: string;
};

function readRelease(dir: string): AppRelease | null {
  try {
    const release = JSON.parse(readFileSync(join(dir, "release.json"), "utf8")) as AppRelease;
    const apk = statSync(join(dir, "app.apk"));
    const valid = Number.isInteger(release.versionCode) && release.versionCode > 0
      && typeof release.versionName === "string"
      && /^[0-9a-f]{64}$/.test(release.sha256)
      && release.size === apk.size;
    return valid ? release : null;
  } catch {
    return null;
  }
}

export function createApp(store: CalendarStore, notifier: DeviceNotifier, config: AppConfig) {
  const app = express();
  const service = new CalendarService(store, notifier);
  const kitchen = new KitchenService(store, undefined, notifier);
  const canvasSync = config.canvas ? new CanvasSyncService(service, config.fetcher ?? fetch) : null;
  // One pairing guard for every pairing-code endpoint, so failures are throttled across all of them.
  const pairing = new PairingGuard(config.pairingCode);
  const registrationLimiter = new AttemptLimiter(20, 60 * 60 * 1000);
  const oauth = new OAuthService(store, config.publicBaseUrl.replace(/\/$/, ""), config.pairingCode, pairing);
  const outlook = config.outlook?.clientId && config.outlook.clientSecret
    ? new OutlookService(store, config.outlook, config.publicBaseUrl.replace(/\/$/, ""), config.pairingCode, config.fetcher ?? fetch, pairing)
    : null;
  app.disable("x-powered-by");
  app.use((_req, res, next) => {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
    res.setHeader("Referrer-Policy", "no-referrer");
    // HTTPS only from now on, for this host. The functions host is HTTPS only anyway; this keeps a
    // browser from ever trying plain HTTP first with a pairing code or a token.
    res.setHeader("Strict-Transport-Security", "max-age=31536000");
    // Reminders, mail and tokens must never sit in a browser or proxy cache.
    res.setHeader("Cache-Control", "no-store");
    next();
  });
  // Sized for the largest valid MCP call (a 700k-character planner_state_put or a 50-item batch
  // with maximum-length notes, UTF-8 encoded), well under the 32 MB Cloud Functions request cap.
  app.use(express.json({ limit: "4mb" }));
  app.use(express.urlencoded({ extended: false, limit: "16kb" }));

  app.get("/health", (_req, res) => res.json({ ok: true, service: "ai-calendar-bridge" }));
  app.get(["/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"], (_req, res) => res.json(oauth.resourceMetadata()));
  app.get(["/.well-known/oauth-authorization-server", "/.well-known/openid-configuration"], (_req, res) => res.json(oauth.serverMetadata()));
  // Returning the promise lets Express 5 route async failures to the error handler.
  app.post("/oauth/register", (req, res) => {
    const key = clientKey(req);
    const retryAfter = registrationLimiter.retryAfter(key);
    if (retryAfter > 0) {
      res.setHeader("Retry-After", String(retryAfter));
      res.status(429).json({ error: "too_many_requests" });
      return;
    }
    registrationLimiter.record(key);
    return oauth.register(req, res);
  });
  app.get("/oauth/authorize", (req, res) => oauth.authorizePage(req, res));
  app.post("/oauth/authorize", (req, res) => oauth.authorize(req, res));
  app.post("/oauth/token", (req, res) => oauth.token(req, res));

  app.get("/outlook/connect", (req, res) => {
    if (!outlook) { res.status(503).send("Outlook is not configured"); return; }
    outlook.connectPage(req, res);
  });
  app.post("/outlook/connect", async (req, res, next) => {
    if (!outlook) { res.status(503).send("Outlook is not configured"); return; }
    try { await outlook.startConnect(req, res); } catch (error) { next(error); }
  });
  app.get("/outlook/callback", async (req, res, next) => {
    if (!outlook) { res.status(503).send("Outlook is not configured"); return; }
    try { await outlook.callback(req, res); } catch (error) { next(error); }
  });

  app.post("/v1/bootstrap", (req, res) => {
    if (!pairingAccepted(res, pairing.check(req, req.body?.pairingCode))) return;
    if (Object.values(config.firebaseClientConfig).some((value) => !value)) {
      res.status(503).json({ error: "Firebase client configuration is incomplete" });
      return;
    }
    res.json({ firebase: config.firebaseClientConfig });
  });

  app.post("/v1/devices/register", async (req, res) => {
    const installationId = typeof req.body?.installationId === "string" ? req.body.installationId.trim() : "";
    const deviceName = typeof req.body?.deviceName === "string" ? req.body.deviceName.trim().slice(0, 120) : "Android device";
    const pushEnabled = req.body?.pushEnabled !== false;
    if (!pairingAccepted(res, pairing.check(req, req.body?.pairingCode))) return;
    if (!installationId || installationId.length > 300) {
      res.status(400).json({ error: "A valid Firebase installation ID is required" });
      return;
    }
    const deviceToken = randomBytes(32).toString("base64url");
    const now = new Date().toISOString();
    const id = deviceRecordId(installationId);
    const existingDevices = await store.listDevices();
    const existing = existingDevices.find((device) => device.id === id);
    await Promise.all(existingDevices
      .filter((device) => device.installationId === installationId && device.id !== id)
      .map((device) => store.deleteDevice(device.id)));
    await store.putDevice({
      id,
      installationId,
      authHash: hashToken(deviceToken),
      deviceName,
      createdAt: existing?.createdAt ?? now,
      lastSeenAt: now,
      pushEnabled,
    });
    res.status(201).json({ deviceToken });
  });

  app.post("/v1/devices/installation-id", async (req, res) => {
    const token = bearerToken(req.header("authorization"));
    const device = token ? await store.findDeviceByAuthHash(hashToken(token)) : null;
    const installationId = typeof req.body?.installationId === "string" ? req.body.installationId.trim() : "";
    if (!device) {
      res.status(401).json({ error: "Unauthorized device" });
      return;
    }
    if (!installationId || installationId.length > 300) {
      res.status(400).json({ error: "A valid Firebase installation ID is required" });
      return;
    }
    // A new installation ID re-enables push for a device FCM had reported as unregistered.
    const { pushInvalidSince: _stale, ...current } = device;
    await store.putDevice({
      ...(installationId === device.installationId ? device : current),
      installationId,
      lastSeenAt: new Date().toISOString(),
    });
    res.status(204).send();
  });

  app.get("/v1/sync", deviceAuth(store), async (_req, res) => {
    res.json(await service.syncSnapshot());
  });

  registerKitchenRoutes(app, store, kitchen);

  app.post("/v1/series", deviceAuth(store), async (req, res) => {
    const invalid = seriesBodyError(req.body, true);
    if (invalid) {
      res.status(400).json({ error: invalid });
      return;
    }
    const created = await service.createSeries({
      ...seriesBodyFields(req.body),
      title: req.body.title,
      timeZone: req.body.timeZone,
      rule: req.body.rule,
      ...(typeof req.body.idempotencyKey === "string" ? { idempotencyKey: req.body.idempotencyKey } : {}),
    });
    res.status(created.created ? 201 : 200).json(created);
  });

  app.patch("/v1/series/:id", deviceAuth(store), async (req, res) => {
    if (!validEventId(req.params.id)) {
      res.status(404).json({ error: "Series not found" });
      return;
    }
    const invalid = seriesBodyError(req.body, false);
    if (invalid) {
      res.status(400).json({ error: invalid });
      return;
    }
    const updated = await service.updateSeries(String(req.params.id), {
      ...seriesBodyFields(req.body),
      ...(typeof req.body.title === "string" ? { title: req.body.title } : {}),
      ...(isValidTimeZone(req.body.timeZone) ? { timeZone: req.body.timeZone } : {}),
      ...(req.body.rule && typeof req.body.rule === "object" ? { rule: req.body.rule } : {}),
      ...(typeof req.body.from === "string" ? { from: req.body.from } : {}),
      ...(typeof req.body.idempotencyKey === "string" ? { idempotencyKey: req.body.idempotencyKey } : {}),
    });
    if (!updated) {
      res.status(404).json({ error: "Series not found" });
      return;
    }
    res.json(updated);
  });

  app.delete("/v1/series/:id", deviceAuth(store), async (req, res) => {
    if (!validEventId(req.params.id)) {
      res.status(404).json({ error: "Series not found" });
      return;
    }
    const from = typeof req.query.from === "string" ? req.query.from : undefined;
    const deleted = await service.deleteSeries(String(req.params.id), from);
    if (!deleted.deleted) {
      res.status(404).json({ error: "Series not found" });
      return;
    }
    res.json(deleted);
  });

  // Phone updates: paired devices only. The phone checks the version, downloads the file, verifies
  // its SHA-256 against this answer, and hands it to Android, which also requires the same signing
  // key as the installed app.
  // Compiled to dist/src/app.js, so the folder beside package.json is two levels up.
  const releaseDir = config.releaseDir ?? fileURLToPath(new URL("../../release/", import.meta.url));
  app.get("/v1/app/latest", deviceAuth(store), (_req, res) => {
    const release = readRelease(releaseDir);
    if (!release) {
      res.status(404).json({ error: "No phone build is published" });
      return;
    }
    res.json(release);
  });

  app.get("/v1/app/apk", deviceAuth(store), (_req, res) => {
    const release = readRelease(releaseDir);
    if (!release) {
      res.status(404).json({ error: "No phone build is published" });
      return;
    }
    res.setHeader("Content-Type", "application/vnd.android.package-archive");
    res.setHeader("Content-Length", String(release.size));
    res.setHeader("X-Content-SHA256", release.sha256);
    createReadStream(join(releaseDir, "app.apk")).pipe(res);
  });

  app.post("/v1/canvas/sync", deviceAuth(store), async (_req, res) => {
    if (!canvasSync || !config.canvas) {
      res.status(503).json({ error: "Canvas is not configured" });
      return;
    }
    res.json(await canvasSync.sync(config.canvas));
  });

  // Done and not done change nothing else, so a device never sends its (possibly older) copy of
  // the rest of the reminder just to tick it.
  for (const [path, done] of [["done", true], ["undone", false]] as const) {
    app.post(`/v1/events/:id/${path}`, deviceAuth(store), async (req, res) => {
      if (!validEventId(req.params.id)) {
        res.status(404).json({ error: "Reminder not found" });
        return;
      }
      const updated = await service.update(String(req.params.id), { done });
      if (!updated) {
        res.status(404).json({ error: "Reminder not found" });
        return;
      }
      res.json({ event: updated.event, notified: updated.notified });
    });
  }

  // A reminder made on a device joins the cloud calendar under the device's own id (a UUID).
  app.post("/v1/events", deviceAuth(store), async (req, res) => {
    const id = typeof req.body?.id === "string" ? req.body.id.trim().toLowerCase() : "";
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) {
      res.status(400).json({ error: "id must be a UUID" });
      return;
    }
    if (typeof req.body?.title !== "string" || typeof req.body?.startAt !== "string") {
      res.status(400).json({ error: "title and startAt are required" });
      return;
    }
    const invalid = deviceEditError(req.body);
    if (invalid) {
      res.status(400).json({ error: invalid });
      return;
    }
    const result = await service.createFromDevice(id, {
      title: req.body.title,
      startAt: req.body.startAt,
      // A phone can report a non-IANA zone ID (e.g. "GMT+05:00"); the instant is exact either way.
      timeZone: isValidTimeZone(req.body.timeZone) ? req.body.timeZone : "UTC",
      location: typeof req.body.location === "string" ? req.body.location : "",
      notes: typeof req.body.notes === "string" ? req.body.notes : "",
      critical: Boolean(req.body.critical),
      done: typeof req.body.done === "boolean" ? req.body.done : false,
      domain: isDomain(req.body.domain) ? req.body.domain : undefined,
      category: isCategory(req.body.category) ? req.body.category : undefined,
      reminderOffsetsMinutes: Array.isArray(req.body.reminderOffsetsMinutes) ? req.body.reminderOffsetsMinutes : undefined,
    });
    if ("conflict" in result) {
      res.status(409).json({ error: result.conflict === "deleted" ? "This reminder was deleted in the cloud" : "That id is already in use" });
      return;
    }
    res.status(result.created ? 201 : 200).json(result);
  });

  // Changes the fields the device sends; the others stay. With `baseRevision` (the revision the
  // device's copy had), an edit made from an older copy is refused with 409 and the current reminder.
  app.patch("/v1/events/:id", deviceAuth(store), async (req, res) => {
    if (!validEventId(req.params.id)) {
      res.status(404).json({ error: "Reminder not found" });
      return;
    }
    const body = (req.body && typeof req.body === "object" ? req.body : {}) as Record<string, unknown>;
    const invalid = deviceEditError(body);
    if (invalid) {
      res.status(400).json({ error: invalid });
      return;
    }
    const input = {
      ...(typeof body.title === "string" ? { title: body.title } : {}),
      ...(typeof body.startAt === "string" ? { startAt: body.startAt } : {}),
      // A phone can report a non-IANA zone ID (e.g. "GMT+05:00"); keep the stored zone rather than corrupt it.
      ...(isValidTimeZone(body.timeZone) ? { timeZone: body.timeZone } : {}),
      ...(typeof body.location === "string" ? { location: body.location } : {}),
      ...(typeof body.notes === "string" ? { notes: body.notes } : {}),
      ...(typeof body.critical === "boolean" ? { critical: body.critical } : {}),
      ...(typeof body.done === "boolean" ? { done: body.done } : {}),
      ...(isDomain(body.domain) ? { domain: body.domain } : {}),
      ...(isCategory(body.category) ? { category: body.category } : {}),
      ...(Array.isArray(body.reminderOffsetsMinutes) ? { reminderOffsetsMinutes: body.reminderOffsetsMinutes as number[] } : {}),
    };
    if (Object.keys(input).length === 0) {
      res.status(400).json({ error: "Nothing to change" });
      return;
    }
    const baseRevision = typeof body.baseRevision === "number" && Number.isFinite(body.baseRevision) ? body.baseRevision : undefined;
    const updated = await service.update(String(req.params.id), input, { baseRevision });
    if (!updated) {
      res.status(404).json({ error: "Reminder not found" });
      return;
    }
    if (updated.conflict) {
      res.status(409).json({ error: "This reminder changed on another device", event: updated.event });
      return;
    }
    res.json({ event: updated.event, notified: updated.notified });
  });

  app.delete("/v1/events/:id", deviceAuth(store), async (req, res) => {
    if (!validEventId(req.params.id)) {
      res.status(404).json({ error: "Reminder not found" });
      return;
    }
    const deleted = await service.delete(String(req.params.id));
    if (!deleted.deleted) {
      res.status(404).json({ error: "Reminder not found" });
      return;
    }
    res.json(deleted);
  });

  app.all("/mcp", apiAuth(config, oauth), async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "MCP endpoint accepts POST only" });
      return;
    }
    const server = createMcpServer(service, canvasSync && config.canvas
      ? () => canvasSync.sync(config.canvas!)
      : undefined, outlook ?? undefined, store, kitchen);
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    res.on("close", () => {
      void transport.close();
      void server.close();
    });
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  });

  app.use((error: unknown, _req: Request, res: Response, _next: NextFunction) => {
    const status = errorStatus(error);
    // Never log the raw error object: body-parser errors carry the request body (pairing codes, tokens).
    if (status >= 500) console.error("Request failed", describeError(error));
    else console.warn("Request rejected", { status, type: (error as { type?: string })?.type, message: (error as Error)?.message });
    if (res.headersSent) return;
    if (error instanceof InputError) res.status(400).json({ error: error.message });
    else if (status === 413) res.status(413).json({ error: "Request body is too large" });
    else if (status === 400) res.status(400).json({ error: "Malformed request body" });
    else if (status < 500) res.status(status).json({ error: "Request rejected" });
    else res.status(500).json({ error: "Internal server error" });
  });

  return app;
}

function pairingAccepted(res: Response, check: PairingCheck): boolean {
  if (check.ok) return true;
  if (check.status === 429) {
    if (check.retryAfter) res.setHeader("Retry-After", String(check.retryAfter));
    res.status(429).json({ error: "Too many attempts. Try again later." });
  } else {
    res.status(401).json({ error: "Invalid pairing code" });
  }
  return false;
}

function errorStatus(error: unknown): number {
  if (error instanceof InputError) return 400;
  const candidate = (error as { status?: unknown; statusCode?: unknown } | null)?.status
    ?? (error as { statusCode?: unknown } | null)?.statusCode;
  return typeof candidate === "number" && candidate >= 400 && candidate < 600 ? candidate : 500;
}

function describeError(error: unknown): Record<string, unknown> {
  if (error instanceof Error) return { name: error.name, message: error.message, code: (error as { code?: unknown }).code, stack: error.stack };
  return { message: String(error) };
}

/** Event IDs are UUIDs; anything else (including an encoded "/") must never reach a Firestore document path. */
function validEventId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

function deviceEditError(body: Record<string, unknown>): string | null {
  if (body.title !== undefined) {
    const title = typeof body.title === "string" ? body.title.trim() : "";
    if (!title || title.length > 500) return "title must be 1-500 characters";
  }
  if (body.startAt !== undefined && (typeof body.startAt !== "string" || Number.isNaN(Date.parse(body.startAt)))) return "startAt must be a valid timestamp";
  if (body.location !== undefined && (typeof body.location !== "string" || body.location.length > 1000)) return "location must be at most 1000 characters";
  if (body.notes !== undefined && (typeof body.notes !== "string" || body.notes.length > 10000)) return "notes must be at most 10000 characters";
  const offsets = body.reminderOffsetsMinutes;
  if (offsets !== undefined && offsets !== null) {
    if (!Array.isArray(offsets) || offsets.length > 20
      || !offsets.every((value) => typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 525600)) {
      return "reminderOffsetsMinutes must be up to 20 numbers between 0 and 525600";
    }
  }
  return null;
}

function seriesBodyError(body: Record<string, unknown> | undefined, create: boolean): string | null {
  if (!body || typeof body !== "object") return "A JSON body is required";
  if (create || body.title !== undefined) {
    const title = typeof body.title === "string" ? body.title.trim() : "";
    if (!title || title.length > 500) return "title must be 1-500 characters";
  }
  if (create && !isValidTimeZone(body.timeZone)) return "timeZone must be a valid IANA time zone";
  if (create && (!body.rule || typeof body.rule !== "object")) return "rule is required";
  if (body.rule !== undefined && (body.rule === null || typeof body.rule !== "object" || Array.isArray(body.rule))) return "rule must be an object";
  if (body.location !== undefined && (typeof body.location !== "string" || body.location.length > 1000)) return "location must be at most 1000 characters";
  if (body.notes !== undefined && (typeof body.notes !== "string" || body.notes.length > 10000)) return "notes must be at most 10000 characters";
  if (body.idempotencyKey !== undefined && (typeof body.idempotencyKey !== "string" || body.idempotencyKey.length < 8 || body.idempotencyKey.length > 200)) {
    return "idempotencyKey must be 8-200 characters";
  }
  return null;
}

/** The optional fields a device may send for a series; the service validates their values. */
function seriesBodyFields(body: Record<string, unknown>) {
  return {
    ...(typeof body.location === "string" ? { location: body.location } : {}),
    ...(typeof body.notes === "string" ? { notes: body.notes } : {}),
    ...(typeof body.critical === "boolean" ? { critical: body.critical } : {}),
    ...(isDomain(body.domain) ? { domain: body.domain } : {}),
    ...(isCategory(body.category) ? { category: body.category } : {}),
    ...(Array.isArray(body.reminderOffsetsMinutes) ? { reminderOffsetsMinutes: body.reminderOffsetsMinutes as number[] } : {}),
  };
}

function deviceRecordId(installationId: string): string {
  return createHash("sha256").update(installationId, "utf8").digest("hex").slice(0, 40);
}

function apiAuth(config: AppConfig, oauth: OAuthService) {
  return async (req: Request, res: Response, next: NextFunction) => {
    const token = bearerToken(req.header("authorization"));
    const staticTokenValid = Boolean(token && config.apiToken && secureEqual(token, config.apiToken));
    // Skip the Firestore lookup when the static token already matched.
    const oauthTokenValid = !staticTokenValid && Boolean(token && token.length <= 512 && await oauth.validateAccessToken(token));
    if (!staticTokenValid && !oauthTokenValid) {
      res.setHeader("WWW-Authenticate", `Bearer resource_metadata="${oauth.baseUrl}/.well-known/oauth-protected-resource", scope="reminders:read reminders:write"`);
      res.status(401).json({ error: "Unauthorized" });
      return;
    }
    next();
  };
}

function deviceAuth(store: CalendarStore) {
  return async (req: Request, res: Response, next: NextFunction) => {
    const token = bearerToken(req.header("authorization"));
    const device = token ? await store.findDeviceByAuthHash(hashToken(token)) : null;
    if (!device) {
      res.setHeader("WWW-Authenticate", "Bearer");
      res.status(401).json({ error: "Unauthorized device" });
      return;
    }
    res.locals.deviceName = device.deviceName || "A device";
    res.locals.deviceId = device.id;
    next();
  };
}

/** Kitchen ids are `k`/`p` plus base36; anything else never reaches a lookup. */
function validKitchenId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(value);
}

function registerKitchenRoutes(app: express.Express, store: CalendarStore, kitchen: KitchenService) {
  const auth = deviceAuth(store);
  const body = (req: Request) => (req.body && typeof req.body === "object" ? req.body : {}) as Record<string, unknown>;
  const revision = async () => (await kitchen.snapshot()).revision;
  const text = (value: unknown) => (typeof value === "string" ? value : undefined);
  // The device that made a change already has the result; the others get a kitchen_sync signal.
  const changed = (res: Response) => kitchen.signal(String(res.locals.deviceId));

  app.get("/v1/kitchen", auth, async (_req, res) => {
    res.json(await kitchen.snapshot());
  });

  app.post("/v1/kitchen/items", auth, async (req, res) => {
    const input = body(req);
    const [added] = await kitchen.addItems([{ name: input.name as string, qty: text(input.qty), note: text(input.note) }], "device", String(res.locals.deviceName));
    await changed(res);
    res.status(added.created ? 201 : 200).json({ revision: await revision(), ...added });
  });

  app.patch("/v1/kitchen/items/:id", auth, async (req, res) => {
    const input = body(req);
    if (input.status !== undefined && input.status !== "need" && input.status !== "got") {
      res.status(400).json({ error: "status must be need or got" });
      return;
    }
    const item = validKitchenId(req.params.id)
      ? await kitchen.updateItem(String(req.params.id), {
        ...(input.name !== undefined ? { name: input.name as string } : {}),
        qty: text(input.qty), note: text(input.note), status: input.status as "need" | "got" | undefined,
      })
      : null;
    if (!item) {
      res.status(404).json({ error: "Item not found" });
      return;
    }
    await changed(res);
    res.json({ revision: await revision(), item });
  });

  app.delete("/v1/kitchen/items/:id", auth, async (req, res) => {
    if (!validKitchenId(req.params.id) || !(await kitchen.deleteItem(String(req.params.id)))) {
      res.status(404).json({ error: "Item not found" });
      return;
    }
    await changed(res);
    res.json({ revision: await revision(), deleted: true });
  });

  app.post("/v1/kitchen/pantry", auth, async (req, res) => {
    const input = body(req);
    const added = await kitchen.addPantryItem({ name: input.name as string, section: text(input.section), status: input.status as PantryStatus | undefined });
    if (added.created) await changed(res);
    res.status(added.created ? 201 : 200).json({ revision: await revision(), ...added });
  });

  app.patch("/v1/kitchen/pantry/:id", auth, async (req, res) => {
    const input = body(req);
    const updated = validKitchenId(req.params.id)
      ? await kitchen.updatePantryItem(String(req.params.id), { status: input.status as PantryStatus | undefined, note: text(input.note), section: text(input.section) })
      : null;
    if (!updated) {
      res.status(404).json({ error: "Pantry item not found" });
      return;
    }
    await changed(res);
    res.json({ revision: await revision(), ...updated });
  });

  app.delete("/v1/kitchen/pantry/:id", auth, async (req, res) => {
    if (!validKitchenId(req.params.id) || !(await kitchen.deletePantryItem(String(req.params.id)))) {
      res.status(404).json({ error: "Pantry item not found" });
      return;
    }
    await changed(res);
    res.json({ revision: await revision(), deleted: true });
  });

  // Corrections to Claude's pantry estimate: out/have apply at once; a note waits for Claude.
  app.post("/v1/kitchen/corrections", auth, async (req, res) => {
    const input = body(req);
    if (input.itemId !== undefined && !validKitchenId(input.itemId)) {
      res.status(404).json({ error: "Pantry item not found" });
      return;
    }
    const added = await kitchen.addCorrection({ kind: input.kind, text: input.text, itemId: input.itemId }, String(res.locals.deviceName));
    if (!added) {
      res.status(404).json({ error: "Pantry item not found" });
      return;
    }
    await changed(res);
    res.status(201).json({ revision: await revision(), ...added });
  });

  app.delete("/v1/kitchen/corrections/:id", auth, async (req, res) => {
    if (!validKitchenId(req.params.id) || !(await kitchen.withdrawCorrection(String(req.params.id)))) {
      res.status(404).json({ error: "No open correction with that id" });
      return;
    }
    await changed(res);
    res.json({ revision: await revision(), deleted: true });
  });

  app.put("/v1/kitchen/notes/:date", auth, async (req, res) => {
    const input = body(req);
    const date = String(req.params.date);
    const note = await kitchen.setNote(date, {
      cooked: input.cooked as NotePatch["cooked"], rating: input.rating as NotePatch["rating"], note: text(input.note),
    });
    await changed(res);
    res.json({ revision: await revision(), date, note });
  });
}

function isDomain(value: unknown): value is EventDomain {
  return value === "life" || value === "school" || value === "delivery";
}

function isCategory(value: unknown): value is EventCategory {
  return typeof value === "string" && [
    "automatic", "school", "medical", "financial", "housing", "travel", "work", "personal", "general", "delivery",
  ].includes(value);
}
