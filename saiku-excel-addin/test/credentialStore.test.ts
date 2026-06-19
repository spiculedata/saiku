import { describe, it, expect, vi, beforeEach } from "vitest";
import type { Credential } from "../src/lib/types";

// Mock Office.context.roamingSettings before importing the module
function makeMockRoaming() {
  const store: Record<string, unknown> = {};
  return {
    get: (key: string) => store[key] ?? undefined,
    set: (key: string, value: unknown) => { store[key] = value; },
    remove: (key: string) => { delete store[key]; },
    saveAsync: (cb: (result: { status: string }) => void) => {
      cb({ status: "succeeded" });
    },
  };
}

let mockRoaming = makeMockRoaming();

vi.stubGlobal("Office", {
  context: {
    get roamingSettings() { return mockRoaming; },
  },
});

// Import after stubbing Office
const { saveCredential, loadCredential, clearCredential } = await import("../src/lib/credentialStore");

describe("credentialStore", () => {
  beforeEach(() => {
    mockRoaming = makeMockRoaming();
  });

  it("saves and loads a credential", async () => {
    const cred: Credential = { kind: "basic", username: "alice", password: "secret" };
    await saveCredential("server-1", cred);
    const loaded = await loadCredential("server-1");
    expect(loaded).toEqual(cred);
  });

  it("clears a credential so it can no longer be loaded", async () => {
    const cred: Credential = { kind: "oidc", accessToken: "tok" };
    await saveCredential("server-2", cred);
    await clearCredential("server-2");
    const loaded = await loadCredential("server-2");
    expect(loaded).toBeNull();
  });

  it("returns null when no credential has been saved", async () => {
    const loaded = await loadCredential("never-saved");
    expect(loaded).toBeNull();
  });
});
