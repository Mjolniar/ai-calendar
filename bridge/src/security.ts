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

/** A pairing code shorter than this is treated as guessable. Generated codes are 43 characters. */
export const STRONG_PAIRING_CODE_LENGTH = 32;

export function isWeakPairingCode(code: string): boolean {
  return code.length > 0 && code.length < STRONG_PAIRING_CODE_LENGTH;
}

/** Startup check. Never prints the code or its length. An empty code disables pairing, which is safe. */
export function warnIfWeakPairingCode(code: string): void {
  if (isWeakPairingCode(code)) {
    console.warn(`AI_CALENDAR_PAIRING_CODE is shorter than ${STRONG_PAIRING_CODE_LENGTH} characters and could be guessed. Replace it with a long random value (desktop/pair-desktop.ps1 makes one). Until then a global limit of failed pairing attempts applies.`);
  }
}

/** Failed pairing attempts counted across every instance (for example in Firestore). */
export interface PairingFailureCounter {
  /** Seconds until pairing is allowed again, or 0. */
  retryAfter(): Promise<number>;
  record(): Promise<void>;
}

type KvStore = {
  getKv(key: string): Promise<Record<string, unknown> | null>;
  mutateKv<T>(key: string, mutate: (current: Record<string, unknown> | null) => { value: Record<string, unknown> | null; result: T }): Promise<T>;
};

/**
 * Fixed one-hour windows in one key-value document: after `max` failures in a window, every pairing
 * attempt is refused until the window ends. Used only with a weak code (see `createApp`): with a
 * strong code it would only let a stranger block the owner's new pairings.
 */
export class KvPairingFailureCounter implements PairingFailureCounter {
  constructor(
    private readonly store: KvStore,
    private readonly max = 50,
    private readonly windowMs = 60 * 60 * 1000,
    private readonly key = "pairingFailures",
  ) {}

  async retryAfter(): Promise<number> {
    const doc = await this.store.getKv(this.key) as { windowStart?: number; count?: number } | null;
    const now = Date.now();
    if (!doc || typeof doc.windowStart !== "number" || doc.windowStart + this.windowMs <= now) return 0;
    return (doc.count ?? 0) >= this.max ? Math.max(1, Math.ceil((doc.windowStart + this.windowMs - now) / 1000)) : 0;
  }

  async record(): Promise<void> {
    const now = Date.now();
    await this.store.mutateKv(this.key, (current) => {
      const windowStart = typeof current?.windowStart === "number" && current.windowStart + this.windowMs > now ? current.windowStart : now;
      const count = windowStart === current?.windowStart && typeof current?.count === "number" ? current.count + 1 : 1;
      return { value: { windowStart, count }, result: undefined };
    });
  }
}

/** Timing-safe pairing-code check shared by every pairing-code endpoint, with failure throttling. */
export class PairingGuard {
  constructor(
    private readonly pairingCode: string,
    private readonly limiter = new AttemptLimiter(10, 15 * 60 * 1000),
    private readonly global?: PairingFailureCounter,
  ) {}

  async check(req: RequestLike, candidate: unknown): Promise<PairingCheck> {
    const key = clientKey(req);
    const retryAfter = Math.max(this.limiter.retryAfter(key), this.global ? await this.global.retryAfter() : 0);
    if (retryAfter > 0) return { ok: false, status: 429, retryAfter };
    if (this.pairingCode && typeof candidate === "string" && secureEqual(candidate, this.pairingCode)) return { ok: true };
    this.limiter.record(key);
    if (this.global) {
      // Counting is best effort: a store failure must not turn a wrong code into a 500.
      await this.global.record().catch((error: unknown) => console.error("Could not count a failed pairing attempt", error instanceof Error ? error.message : error));
    }
    return { ok: false, status: 401 };
  }
}
