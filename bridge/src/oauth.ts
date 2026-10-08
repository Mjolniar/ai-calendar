import { createHash, randomBytes, randomUUID } from "node:crypto";
import type { Request, Response } from "express";
import type { CalendarStore } from "./store.js";
import { hashToken, PairingGuard, secureEqual } from "./security.js";

const ACCESS_TTL_MS = 60 * 60 * 1000;
const REFRESH_TTL_MS = 90 * 24 * 60 * 60 * 1000;
const CODE_TTL_MS = 5 * 60 * 1000;
/** A rotated refresh token stays usable briefly so a client retry after a lost response does not strand it. */
const REFRESH_REUSE_GRACE_MS = 2 * 60 * 1000;
const AUTHORIZE_PARAMS = ["response_type", "client_id", "redirect_uri", "code_challenge", "code_challenge_method", "resource", "scope", "state"] as const;
export const OAUTH_SCOPE = "reminders:read reminders:write";

export class OAuthService {
  constructor(
    private readonly store: CalendarStore,
    readonly baseUrl: string,
    private readonly pairingCode: string,
    private readonly pairing: PairingGuard = new PairingGuard(pairingCode),
  ) {}

  get resource(): string {
    return `${this.baseUrl}/mcp`;
  }

  resourceMetadata() {
    return {
      resource: this.resource,
      authorization_servers: [this.baseUrl],
      scopes_supported: OAUTH_SCOPE.split(" "),
    };
  }

  serverMetadata() {
    return {
      issuer: this.baseUrl,
      authorization_endpoint: `${this.baseUrl}/oauth/authorize`,
      token_endpoint: `${this.baseUrl}/oauth/token`,
      registration_endpoint: `${this.baseUrl}/oauth/register`,
      token_endpoint_auth_methods_supported: ["none"],
      code_challenge_methods_supported: ["S256"],
      scopes_supported: OAUTH_SCOPE.split(" "),
      response_types_supported: ["code"],
      grant_types_supported: ["authorization_code", "refresh_token"],
    };
  }

  async register(req: Request, res: Response): Promise<void> {
    const redirectUris = Array.isArray(req.body?.redirect_uris) ? req.body.redirect_uris.filter((value: unknown) => typeof value === "string") : [];
    if (redirectUris.length === 0 || redirectUris.length > 10 || !redirectUris.every(isAllowedRedirect)) {
      res.status(400).json({ error: "invalid_redirect_uri" });
      return;
    }
    const clientId = randomUUID();
    const clientName = typeof req.body?.client_name === "string" && req.body.client_name.trim()
      ? req.body.client_name.trim().slice(0, 120)
      : "ChatGPT";
    await this.store.putOAuthClient({
      clientId,
      clientName,
      redirectUris,
      createdAt: new Date().toISOString(),
    });
    res.status(201).json({
      client_id: clientId,
      client_name: clientName,
      redirect_uris: redirectUris,
      token_endpoint_auth_method: "none",
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
    });
  }

  async authorizePage(req: Request, res: Response): Promise<void> {
    const validation = await this.validateAuthorizeRequest(req.query as Record<string, unknown>);
    if (typeof validation === "string") {
      res.status(400).type("text").send(validation);
      return;
    }
    const client = await this.store.getOAuthClient(validation.client_id);
    const redirectHost = new URL(validation.redirect_uri).host;
    const fields = Object.entries(validation)
      .map(([name, value]) => `<input type="hidden" name="${escapeHtml(name)}" value="${escapeHtml(value)}">`)
      .join("");
    res.type("html").send(`<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>Authorize AI Calendar</title><style>body{font-family:system-ui;max-width:420px;margin:48px auto;padding:20px;color:#1f2937}input,button{box-sizing:border-box;width:100%;padding:12px;margin-top:12px;font:inherit}button{background:#176b3a;color:white;border:0;border-radius:6px}p{line-height:1.5}</style></head><body><h1>Connect AI Calendar</h1><p>Allow your AI assistant to manage reminders on your paired phone and use the connected Outlook mailbox for planning.</p><p>Client: <strong>${escapeHtml(client?.clientName ?? "unknown")}</strong><br>Returns to: <strong>${escapeHtml(redirectHost)}</strong></p><p>Only continue if you started this connection yourself.</p><form method="post" action="${escapeHtml(this.baseUrl)}/oauth/authorize">${fields}<label>Pairing code<input name="pairing_code" type="password" autocomplete="one-time-code" required></label><button type="submit">Authorize</button></form></body></html>`);
  }

  async authorize(req: Request, res: Response): Promise<void> {
    const input = (req.body ?? {}) as Record<string, unknown>;
    const validation = await this.validateAuthorizeRequest(input);
    if (typeof validation === "string") {
      res.status(400).type("text").send(validation);
      return;
    }
    const pairing = this.pairing.check(req, input.pairing_code);
    if (!pairing.ok) {
      if (pairing.retryAfter) res.setHeader("Retry-After", String(pairing.retryAfter));
      res.status(pairing.status).type("text").send(pairing.status === 429 ? "Too many attempts. Try again later." : "Invalid pairing code");
      return;
    }
    const code = randomToken();
    await this.store.putOAuthCode({
      codeHash: hashToken(code),
      clientId: validation.client_id,
      redirectUri: validation.redirect_uri,
      codeChallenge: validation.code_challenge,
      resource: validation.resource,
      scope: validation.scope,
      expiresAt: Date.now() + CODE_TTL_MS,
    });
    const redirect = new URL(validation.redirect_uri);
    redirect.searchParams.set("code", code);
    if (validation.state) redirect.searchParams.set("state", validation.state);
    res.redirect(303, redirect.toString());
  }

  async token(req: Request, res: Response): Promise<void> {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Pragma", "no-cache");
    const grantType = req.body?.grant_type;
    if (grantType === "authorization_code") {
      await this.exchangeCode(req, res);
      return;
    }
    if (grantType === "refresh_token") {
      await this.exchangeRefreshToken(req, res);
      return;
    }
    tokenError(res, "unsupported_grant_type");
  }

  async validateAccessToken(rawToken: string): Promise<boolean> {
    const token = await this.store.getOAuthToken(hashToken(rawToken));
    return token?.kind === "access" && token.expiresAt > Date.now() && token.resource === this.resource && hasRequiredScopes(token.scope);
  }

  private async exchangeCode(req: Request, res: Response): Promise<void> {
    const rawCode = typeof req.body?.code === "string" ? req.body.code : "";
    const code = await this.store.takeOAuthCode(hashToken(rawCode));
    if (!code || code.expiresAt <= Date.now()) {
      tokenError(res, "invalid_grant");
      return;
    }
    const verifier = typeof req.body?.code_verifier === "string" ? req.body.code_verifier : "";
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    if (
      !secureEqual(challenge, code.codeChallenge) ||
      req.body?.client_id !== code.clientId ||
      req.body?.redirect_uri !== code.redirectUri ||
      !resourceMatches(req.body?.resource, code.resource)
    ) {
      tokenError(res, "invalid_grant");
      return;
    }
    await this.issueTokens(res, code.clientId, code.resource, code.scope);
  }

  private async exchangeRefreshToken(req: Request, res: Response): Promise<void> {
    const rawToken = typeof req.body?.refresh_token === "string" ? req.body.refresh_token : "";
    if (!rawToken) {
      tokenError(res, "invalid_grant");
      return;
    }
    const tokenHash = hashToken(rawToken);
    const token = await this.store.getOAuthToken(tokenHash);
    if (!token || token.kind !== "refresh" || token.expiresAt <= Date.now() || req.body?.client_id !== token.clientId || !resourceMatches(req.body?.resource, token.resource)) {
      tokenError(res, "invalid_grant");
      return;
    }
    // Rotate: the presented refresh token is retired after a short grace window (OAuth 2.1 public clients).
    const rotated = await this.store.rotateOAuthRefreshToken(tokenHash, REFRESH_REUSE_GRACE_MS);
    if (!rotated) {
      tokenError(res, "invalid_grant");
      return;
    }
    await this.issueTokens(res, token.clientId, token.resource, token.scope);
  }

  private async issueTokens(res: Response, clientId: string, resource: string, scope: string): Promise<void> {
    const accessToken = randomToken();
    const refreshToken = randomToken();
    await Promise.all([
      this.store.putOAuthToken({ tokenHash: hashToken(accessToken), kind: "access", clientId, resource, scope, expiresAt: Date.now() + ACCESS_TTL_MS }),
      this.store.putOAuthToken({ tokenHash: hashToken(refreshToken), kind: "refresh", clientId, resource, scope, expiresAt: Date.now() + REFRESH_TTL_MS }),
    ]);
    res.json({
      access_token: accessToken,
      token_type: "Bearer",
      expires_in: ACCESS_TTL_MS / 1000,
      refresh_token: refreshToken,
      scope,
    });
  }

  private async validateAuthorizeRequest(raw: Record<string, unknown>): Promise<Record<string, string> | string> {
    // Query/form parsers can yield arrays or objects for repeated keys; accept only plain strings.
    const input: Partial<Record<(typeof AUTHORIZE_PARAMS)[number], string>> = {};
    for (const name of AUTHORIZE_PARAMS) {
      const value = raw?.[name];
      if (value === undefined) continue;
      if (typeof value !== "string") return `Invalid ${name} parameter`;
      input[name] = value;
    }
    if (input.response_type !== "code") return "Unsupported response type";
    if (input.code_challenge_method !== "S256" || !input.code_challenge) return "PKCE S256 is required";
    if (!/^[A-Za-z0-9_-]{43}$/.test(input.code_challenge)) return "Invalid PKCE code challenge";
    if ((input.state ?? "").length > 2048) return "State is too long";
    const resource = input.resource || this.resource;
    if (resource !== this.resource && resource !== `${this.resource}/`) return "Invalid resource audience";
    const scope = input.scope ? (hasRequiredScopes(input.scope) ? input.scope : `${input.scope} ${OAUTH_SCOPE}`) : OAUTH_SCOPE;
    if (!input.client_id || !input.redirect_uri || !/^[A-Za-z0-9_-]{1,100}$/.test(input.client_id)) return "Invalid OAuth client or redirect URI";
    const client = await this.store.getOAuthClient(input.client_id);
    if (!client || !client.redirectUris.includes(input.redirect_uri)) return "Invalid OAuth client or redirect URI";
    return {
      response_type: "code",
      client_id: input.client_id,
      redirect_uri: input.redirect_uri,
      code_challenge: input.code_challenge,
      code_challenge_method: "S256",
      resource: this.resource,
      scope,
      state: input.state ?? "",
    };
  }
}

function resourceMatches(value: unknown, expected: string): boolean {
  if (value === undefined || value === null || value === "") return true;
  return value === expected || value === `${expected}/`;
}

function randomToken(): string {
  return randomBytes(32).toString("base64url");
}

function hasRequiredScopes(scope: string): boolean {
  const values = new Set(scope.split(/\s+/));
  return OAUTH_SCOPE.split(" ").every((value) => values.has(value));
}

function isAllowedRedirect(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "https:" || (url.protocol === "http:" && ["127.0.0.1", "localhost"].includes(url.hostname));
  } catch {
    return false;
  }
}

function tokenError(res: Response, error: string): void {
  res.status(400).json({ error });
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>'"]/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[character] ?? character);
}
