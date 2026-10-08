import { createHash, timingSafeEqual } from "node:crypto";

export function hashToken(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export function secureEqual(left: string, right: string): boolean {
  // Compare fixed-length digests so neither content nor length leaks through timing.
  const leftDigest = createHash("sha256").update(String(left), "utf8").digest();
  const rightDigest = createHash("sha256").update(String(right), "utf8").digest();
  return timingSafeEqual(leftDigest, rightDigest) && left === right;
}

export function bearerToken(header: string | undefined): string | null {
  if (!header?.startsWith("Bearer ")) return null;
  const token = header.slice("Bearer ".length).trim();
  return token.length > 0 ? token : null;
}

type RequestLike = {
  header(name: string): string | undefined;
  socket?: { remoteAddress?: string };
};

/**
 * Best-effort client key. Cloud Run / Cloud Functions append the real client
 * address as the last X-Forwarded-For hop, so the rightmost entry cannot be
 * spoofed by the caller. Falls back to the socket address.
 */
export function clientKey(req: RequestLike): string {
  const forwarded = req.header("x-forwarded-for");
  const last = forwarded?.split(",").map((part) => part.trim()).filter(Boolean).at(-1);
  return (last || req.socket?.remoteAddress || "unknown").slice(0, 64);
}

/**
 * In-memory sliding-window counter, per function instance. It is defense in
 * depth against brute force and write amplification, not a global quota.
 */
export class AttemptLimiter {
  private readonly entries = new Map<string, { count: number; resetAt: number }>();

  constructor(
    private readonly max: number,
    private readonly windowMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Seconds until the key may try again, or 0 when not limited. */
  retryAfter(key: string): number {
    const entry = this.entries.get(key);
    const now = this.now();
    if (!entry || entry.resetAt <= now) return 0;
    return entry.count >= this.max ? Math.max(1, Math.ceil((entry.resetAt - now) / 1000)) : 0;
  }

  record(key: string): void {
    const now = this.now();
    const entry = this.entries.get(key);
    if (!entry || entry.resetAt <= now) {
      if (this.entries.size >= 5000) this.prune(now);
      this.entries.set(key, { count: 1, resetAt: now + this.windowMs });
      return;
    }
    entry.count += 1;
  }

  private prune(now: number): void {
    for (const [key, entry] of this.entries) {
      if (entry.resetAt <= now) this.entries.delete(key);
    }
    if (this.entries.size >= 5000) this.entries.clear();
  }
}

export type PairingCheck = { ok: true } | { ok: false; status: 401 | 429; retryAfter?: number };

/** Timing-safe pairing-code check shared by every pairing-code endpoint, with failure throttling. */
export class PairingGuard {
  constructor(
    private readonly pairingCode: string,
    private readonly limiter = new AttemptLimiter(10, 15 * 60 * 1000),
  ) {}

  check(req: RequestLike, candidate: unknown): PairingCheck {
    const key = clientKey(req);
    const retryAfter = this.limiter.retryAfter(key);
    if (retryAfter > 0) return { ok: false, status: 429, retryAfter };
    if (this.pairingCode && typeof candidate === "string" && secureEqual(candidate, this.pairingCode)) return { ok: true };
    this.limiter.record(key);
    return { ok: false, status: 401 };
  }
}
