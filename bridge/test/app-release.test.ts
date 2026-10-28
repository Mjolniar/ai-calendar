import request from "supertest";
import { createHash } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { NoopNotifier } from "../src/notifier.js";
import { MemoryCalendarStore } from "../src/store.js";

async function paired(releaseDir: string) {
  const app = createApp(new MemoryCalendarStore(), new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: "654321",
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: "https://calendar.example.test/api",
    releaseDir,
  });
  const phone = await request(app).post("/v1/devices/register").send({ pairingCode: "654321", installationId: "phone-1" }).expect(201);
  return { app, auth: `Bearer ${phone.body.deviceToken}` };
}

function publish(dir: string, apk: Buffer, overrides: Record<string, unknown> = {}) {
  writeFileSync(join(dir, "app.apk"), apk);
  writeFileSync(join(dir, "release.json"), JSON.stringify({
    versionCode: 27,
    versionName: "0.15.0",
    sha256: createHash("sha256").update(apk).digest("hex"),
    size: apk.length,
    publishedAt: "2026-10-21T18:00:00Z",
    ...overrides,
  }));
}

describe("phone updates", () => {
  it("offers the published build to paired devices only, byte for byte", async () => {
    const dir = mkdtempSync(join(tmpdir(), "release-"));
    const apk = Buffer.from("PK\u0003\u0004 not really an apk, but bytes are bytes");
    publish(dir, apk);
    const { app, auth } = await paired(dir);

    await request(app).get("/v1/app/latest").expect(401);
    await request(app).get("/v1/app/apk").expect(401);

    const latest = await request(app).get("/v1/app/latest").set("Authorization", auth).expect(200);
    expect(latest.body).toMatchObject({ versionCode: 27, versionName: "0.15.0", size: apk.length });

    const download = await request(app).get("/v1/app/apk").set("Authorization", auth)
      .buffer(true).parse((res, done) => {
        const chunks: Buffer[] = [];
        res.on("data", (chunk: Buffer) => chunks.push(chunk));
        res.on("end", () => done(null, Buffer.concat(chunks)));
      }).expect(200);
    expect(download.headers["content-type"]).toBe("application/vnd.android.package-archive");
    expect(createHash("sha256").update(download.body as Buffer).digest("hex")).toBe(latest.body.sha256);
  });

  it("offers nothing when no build is published or the files disagree", async () => {
    const empty = await paired(mkdtempSync(join(tmpdir(), "release-")));
    await request(empty.app).get("/v1/app/latest").set("Authorization", empty.auth).expect(404);

    const dir = mkdtempSync(join(tmpdir(), "release-"));
    publish(dir, Buffer.from("abc"), { size: 999 });
    const mismatched = await paired(dir);
    await request(mismatched.app).get("/v1/app/latest").set("Authorization", mismatched.auth).expect(404);
    await request(mismatched.app).get("/v1/app/apk").set("Authorization", mismatched.auth).expect(404);
  });
});
