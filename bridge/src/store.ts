import { getFirestore, Timestamp, type Firestore } from "firebase-admin/firestore";
import type { DeviceRecord, OAuthClient, OAuthCode, OAuthToken, ReminderEvent, ReminderSeries } from "./types.js";

export interface CalendarStore {
  listEvents(includeDone?: boolean): Promise<ReminderEvent[]>;
  getEvent(id: string): Promise<ReminderEvent | null>;
  findEventByIdempotencyKey(key: string): Promise<ReminderEvent | null>;
  putEvent(event: ReminderEvent): Promise<void>;
  /**
   * Atomic read-modify-write of one event. `mutate` receives the stored record (including a
   * tombstone) or null and returns the record to write, or null to leave it unchanged. It must be
   * pure: Firestore may call it more than once when a transaction retries.
   */
  mutateEvent(id: string, mutate: (current: ReminderEvent | null) => ReminderEvent | null): Promise<EventMutation>;
  deleteEvent(id: string): Promise<boolean>;
  /** Every series, deleted ones (tombstones) included. */
  listSeries(): Promise<ReminderSeries[]>;
  getSeries(id: string): Promise<ReminderSeries | null>;
  /** Atomic read-modify-write of one series, with the same contract as `mutateEvent`. */
  mutateSeries(id: string, mutate: (current: ReminderSeries | null) => ReminderSeries | null): Promise<SeriesMutation>;
  listDevices(): Promise<DeviceRecord[]>;
  findDeviceByAuthHash(hash: string): Promise<DeviceRecord | null>;
  putDevice(device: DeviceRecord): Promise<void>;
  deleteDevice(id: string): Promise<void>;
  /** Merge fields into an existing device record; a missing device is ignored. */
  patchDevice(id: string, fields: Partial<DeviceRecord>): Promise<void>;
  putOAuthClient(client: OAuthClient): Promise<void>;
  getOAuthClient(clientId: string): Promise<OAuthClient | null>;
  putOAuthCode(code: OAuthCode): Promise<void>;
  takeOAuthCode(codeHash: string): Promise<OAuthCode | null>;
  putOAuthToken(token: OAuthToken): Promise<void>;
  getOAuthToken(tokenHash: string): Promise<OAuthToken | null>;
  /**
   * Atomically marks a refresh token as rotated and shortens its lifetime to `graceMs`.
   * Returns the token when it may still be exchanged (first use, or a retry inside the grace window).
   */
  rotateOAuthRefreshToken(tokenHash: string, graceMs: number): Promise<OAuthToken | null>;
  getKv(key: string): Promise<Record<string, unknown> | null>;
  putKv(key: string, value: Record<string, unknown>): Promise<void>;
  takeKv(key: string): Promise<Record<string, unknown> | null>;
  /** Shallow-merge top-level fields into a key-value document (creating it when absent). */
  mergeKv(key: string, fields: Record<string, unknown>): Promise<void>;
}

export type EventMutation = { before: ReminderEvent | null; after: ReminderEvent | null };
export type SeriesMutation = { before: ReminderSeries | null; after: ReminderSeries | null };

const TOMBSTONE_TTL_MS = 400 * 24 * 60 * 60 * 1000;

function usableRefreshToken(token: OAuthToken | null, now: number): token is OAuthToken {
  return Boolean(token && token.kind === "refresh" && token.expiresAt > now);
}

export class MemoryCalendarStore implements CalendarStore {
  private readonly events = new Map<string, ReminderEvent>();
  private readonly series = new Map<string, ReminderSeries>();
  private readonly devices = new Map<string, DeviceRecord>();
  private readonly oauthClients = new Map<string, OAuthClient>();
  private readonly oauthCodes = new Map<string, OAuthCode>();
  private readonly oauthTokens = new Map<string, OAuthToken>();
  private readonly kv = new Map<string, Record<string, unknown>>();

  async getKv(key: string): Promise<Record<string, unknown> | null> {
    return this.kv.get(key) ?? null;
  }

  async putKv(key: string, value: Record<string, unknown>): Promise<void> {
    this.kv.set(key, value);
  }

  async takeKv(key: string): Promise<Record<string, unknown> | null> {
    const value = this.kv.get(key) ?? null;
    this.kv.delete(key);
    return value;
  }

  async mergeKv(key: string, fields: Record<string, unknown>): Promise<void> {
    this.kv.set(key, { ...(this.kv.get(key) ?? {}), ...fields });
  }

  async listEvents(includeDone = false): Promise<ReminderEvent[]> {
    return [...this.events.values()]
      .filter((event) => includeDone || !event.done)
      .sort((a, b) => a.startAt.localeCompare(b.startAt));
  }

  async getEvent(id: string): Promise<ReminderEvent | null> {
    return this.events.get(id) ?? null;
  }

  async findEventByIdempotencyKey(key: string): Promise<ReminderEvent | null> {
    return [...this.events.values()].find((event) => event.idempotencyKey === key) ?? null;
  }

  async putEvent(event: ReminderEvent): Promise<void> {
    this.events.set(event.id, event);
  }

  async mutateEvent(id: string, mutate: (current: ReminderEvent | null) => ReminderEvent | null): Promise<EventMutation> {
    // Synchronous read-modify-write: atomic on the single-threaded event loop.
    const before = this.events.get(id) ?? null;
    const after = mutate(before ? structuredClone(before) : null);
    if (after) this.events.set(id, after);
    return { before, after };
  }

  async deleteEvent(id: string): Promise<boolean> {
    return this.events.delete(id);
  }

  async listSeries(): Promise<ReminderSeries[]> {
    return [...this.series.values()].map((series) => structuredClone(series));
  }

  async getSeries(id: string): Promise<ReminderSeries | null> {
    const series = this.series.get(id);
    return series ? structuredClone(series) : null;
  }

  async mutateSeries(id: string, mutate: (current: ReminderSeries | null) => ReminderSeries | null): Promise<SeriesMutation> {
    const before = this.series.get(id) ?? null;
    const after = mutate(before ? structuredClone(before) : null);
    if (after) this.series.set(id, after);
    return { before, after };
  }

  async listDevices(): Promise<DeviceRecord[]> {
    return [...this.devices.values()];
  }

  async findDeviceByAuthHash(hash: string): Promise<DeviceRecord | null> {
    return [...this.devices.values()].find((device) => device.authHash === hash) ?? null;
  }

  async putDevice(device: DeviceRecord): Promise<void> {
    this.devices.set(device.id, device);
  }

  async deleteDevice(id: string): Promise<void> {
    this.devices.delete(id);
  }

  async patchDevice(id: string, fields: Partial<DeviceRecord>): Promise<void> {
    const device = this.devices.get(id);
    if (device) this.devices.set(id, { ...device, ...fields, id });
  }

  async putOAuthClient(client: OAuthClient): Promise<void> {
    this.oauthClients.set(client.clientId, client);
  }

  async getOAuthClient(clientId: string): Promise<OAuthClient | null> {
    return this.oauthClients.get(clientId) ?? null;
  }

  async putOAuthCode(code: OAuthCode): Promise<void> {
    this.oauthCodes.set(code.codeHash, code);
  }

  async takeOAuthCode(codeHash: string): Promise<OAuthCode | null> {
    const code = this.oauthCodes.get(codeHash) ?? null;
    this.oauthCodes.delete(codeHash);
    return code;
  }

  async putOAuthToken(token: OAuthToken): Promise<void> {
    this.oauthTokens.set(token.tokenHash, token);
  }

  async getOAuthToken(tokenHash: string): Promise<OAuthToken | null> {
    return this.oauthTokens.get(tokenHash) ?? null;
  }

  async rotateOAuthRefreshToken(tokenHash: string, graceMs: number): Promise<OAuthToken | null> {
    const now = Date.now();
    const token = this.oauthTokens.get(tokenHash) ?? null;
    if (!usableRefreshToken(token, now)) return null;
    if (token.rotatedAt === undefined) {
      this.oauthTokens.set(tokenHash, { ...token, rotatedAt: now, expiresAt: Math.min(token.expiresAt, now + graceMs) });
    }
    return token;
  }
}

export class FirestoreCalendarStore implements CalendarStore {
  constructor(private readonly db: Firestore = getFirestore()) {}

  private kvRef(key: string) {
    return this.db.collection("integrations").doc(key.replace(/[^A-Za-z0-9_-]/g, "_"));
  }

  async getKv(key: string): Promise<Record<string, unknown> | null> {
    const snapshot = await this.kvRef(key).get();
    return snapshot.exists ? (snapshot.data() as Record<string, unknown>) : null;
  }

  async putKv(key: string, value: Record<string, unknown>): Promise<void> {
    // Short-lived records (e.g. Outlook sign-in state) get a TTL field for a Firestore TTL policy.
    await this.kvRef(key).set(typeof value.expiresAt === "number" ? { ...value, expireAt: ttl(value.expiresAt) } : value);
  }

  async mergeKv(key: string, fields: Record<string, unknown>): Promise<void> {
    await this.kvRef(key).set(fields, { merge: true });
  }

  async takeKv(key: string): Promise<Record<string, unknown> | null> {
    const ref = this.kvRef(key);
    return this.db.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(ref);
      if (!snapshot.exists) return null;
      transaction.delete(ref);
      return snapshot.data() as Record<string, unknown>;
    });
  }

  async listEvents(includeDone = false): Promise<ReminderEvent[]> {
    const snapshot = await this.db.collection("events").get();
    return snapshot.docs
      .map((doc) => doc.data() as ReminderEvent)
      .filter((event) => includeDone || !event.done)
      .sort((a, b) => a.startAt.localeCompare(b.startAt));
  }

  async getEvent(id: string): Promise<ReminderEvent | null> {
    const snapshot = await this.db.collection("events").doc(id).get();
    return snapshot.exists ? (snapshot.data() as ReminderEvent) : null;
  }

  async findEventByIdempotencyKey(key: string): Promise<ReminderEvent | null> {
    const snapshot = await this.db.collection("events")
      .where("idempotencyKey", "==", key)
      .limit(1)
      .get();
    return snapshot.empty ? null : (snapshot.docs[0].data() as ReminderEvent);
  }

  async putEvent(event: ReminderEvent): Promise<void> {
    await this.db.collection("events").doc(event.id).set(withEventTtl(event));
  }

  async mutateEvent(id: string, mutate: (current: ReminderEvent | null) => ReminderEvent | null): Promise<EventMutation> {
    const ref = this.db.collection("events").doc(id);
    return this.db.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(ref);
      const before = snapshot.exists ? (snapshot.data() as ReminderEvent) : null;
      const after = mutate(before ? structuredClone(before) : null);
      if (after) transaction.set(ref, withEventTtl(after));
      return { before, after };
    });
  }

  async deleteEvent(id: string): Promise<boolean> {
    const ref = this.db.collection("events").doc(id);
    const snapshot = await ref.get();
    if (!snapshot.exists) return false;
    await ref.delete();
    return true;
  }

  async listSeries(): Promise<ReminderSeries[]> {
    const snapshot = await this.db.collection("series").get();
    return snapshot.docs.map((doc) => doc.data() as ReminderSeries);
  }

  async getSeries(id: string): Promise<ReminderSeries | null> {
    const snapshot = await this.db.collection("series").doc(id).get();
    return snapshot.exists ? (snapshot.data() as ReminderSeries) : null;
  }

  async mutateSeries(id: string, mutate: (current: ReminderSeries | null) => ReminderSeries | null): Promise<SeriesMutation> {
    const ref = this.db.collection("series").doc(id);
    return this.db.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(ref);
      const before = snapshot.exists ? (snapshot.data() as ReminderSeries) : null;
      const after = mutate(before ? structuredClone(before) : null);
      if (after) transaction.set(ref, stripUndefined(after));
      return { before, after };
    });
  }

  async listDevices(): Promise<DeviceRecord[]> {
    const snapshot = await this.db.collection("devices").get();
    return snapshot.docs.map((doc) => doc.data() as DeviceRecord);
  }

  async findDeviceByAuthHash(hash: string): Promise<DeviceRecord | null> {
    const snapshot = await this.db.collection("devices")
      .where("authHash", "==", hash)
      .limit(1)
      .get();
    return snapshot.empty ? null : (snapshot.docs[0].data() as DeviceRecord);
  }

  async putDevice(device: DeviceRecord): Promise<void> {
    await this.db.collection("devices").doc(device.id).set(device);
  }

  async deleteDevice(id: string): Promise<void> {
    await this.db.collection("devices").doc(id).delete();
  }

  async patchDevice(id: string, fields: Partial<DeviceRecord>): Promise<void> {
    try {
      await this.db.collection("devices").doc(id).update(fields);
    } catch (error) {
      if ((error as { code?: number }).code !== 5) throw error; // 5 = NOT_FOUND
    }
  }

  async putOAuthClient(client: OAuthClient): Promise<void> {
    await this.db.collection("oauthClients").doc(client.clientId).set(client);
  }

  async getOAuthClient(clientId: string): Promise<OAuthClient | null> {
    const snapshot = await this.db.collection("oauthClients").doc(clientId).get();
    return snapshot.exists ? (snapshot.data() as OAuthClient) : null;
  }

  async putOAuthCode(code: OAuthCode): Promise<void> {
    await this.db.collection("oauthCodes").doc(code.codeHash).set({ ...code, expireAt: ttl(code.expiresAt) });
  }

  async takeOAuthCode(codeHash: string): Promise<OAuthCode | null> {
    const ref = this.db.collection("oauthCodes").doc(codeHash);
    return this.db.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(ref);
      if (!snapshot.exists) return null;
      transaction.delete(ref);
      return snapshot.data() as OAuthCode;
    });
  }

  async putOAuthToken(token: OAuthToken): Promise<void> {
    await this.db.collection("oauthTokens").doc(token.tokenHash).set({ ...token, expireAt: ttl(token.expiresAt) });
  }

  async getOAuthToken(tokenHash: string): Promise<OAuthToken | null> {
    const snapshot = await this.db.collection("oauthTokens").doc(tokenHash).get();
    return snapshot.exists ? (snapshot.data() as OAuthToken) : null;
  }

  async rotateOAuthRefreshToken(tokenHash: string, graceMs: number): Promise<OAuthToken | null> {
    const ref = this.db.collection("oauthTokens").doc(tokenHash);
    return this.db.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(ref);
      const token = snapshot.exists ? (snapshot.data() as OAuthToken) : null;
      const now = Date.now();
      if (!usableRefreshToken(token, now)) return null;
      if (token.rotatedAt === undefined) {
        const expiresAt = Math.min(token.expiresAt, now + graceMs);
        transaction.update(ref, { rotatedAt: now, expiresAt, expireAt: ttl(expiresAt) });
      }
      return token;
    });
  }
}

/** Firestore TTL policies need a Timestamp field; configure TTL on `expireAt` for oauthCodes, oauthTokens, integrations, events. */
function ttl(expiresAtMs: number): Timestamp {
  return Timestamp.fromMillis(expiresAtMs);
}

/** Firestore rejects `undefined` values; an optional rule field that was cleared is left out instead. */
function stripUndefined<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function withEventTtl(event: ReminderEvent): ReminderEvent {
  if (!event.deletedAt) return event;
  const deletedAt = Date.parse(event.deletedAt);
  return { ...event, expireAt: ttl((Number.isNaN(deletedAt) ? Date.now() : deletedAt) + TOMBSTONE_TTL_MS) } as ReminderEvent;
}
