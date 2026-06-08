/*
 * Unit tests for recentDashboards (#936).
 *
 * The store reads session.current.username at every API call. We can't
 * easily mutate Svelte's $state singleton from a test, so we mock the
 * session module wholesale before importing the store under test.
 */

import { beforeEach, describe, expect, it, vi } from "vitest";

let currentUsername: string | null = "alice";

vi.mock("$lib/stores/session.svelte", () => ({
  session: {
    get current() {
      return currentUsername == null ? null : { username: currentUsername };
    },
  },
}));

// Tiny in-memory localStorage replacement so tests are deterministic
// and isolated. jsdom would give us window.localStorage, but pulling
// in jsdom for these is overkill — the store only uses get/set/clear.
function installFakeLocalStorage(): void {
  const store = new Map<string, string>();
  const fakeStorage: Storage = {
    getItem: (k) => store.get(k) ?? null,
    setItem: (k, v) => {
      store.set(k, v);
    },
    removeItem: (k) => {
      store.delete(k);
    },
    clear: () => store.clear(),
    get length() {
      return store.size;
    },
    key: (i) => Array.from(store.keys())[i] ?? null,
  };
  vi.stubGlobal("window", { localStorage: fakeStorage });
}

let recentDashboards: typeof import("./recentDashboards.svelte").recentDashboards;
let RECENTS_CAP: number;

beforeEach(async () => {
  installFakeLocalStorage();
  currentUsername = "alice";
  // Force a fresh module instance so the singleton's primed state
  // resets between tests.
  vi.resetModules();
  const mod = await import("./recentDashboards.svelte");
  recentDashboards = mod.recentDashboards;
  RECENTS_CAP = mod.RECENTS_CAP;
});

describe("recentDashboards", () => {
  it("starts empty for a fresh user", () => {
    expect(recentDashboards.all()).toEqual([]);
  });

  it("push() adds the path to the front", () => {
    recentDashboards.push("homes/alice/sales.saikudash");
    expect(recentDashboards.all()).toEqual(["homes/alice/sales.saikudash"]);
  });

  it("multiple push() calls keep most-recent-first ordering", () => {
    recentDashboards.push("a.saikudash");
    recentDashboards.push("b.saikudash");
    recentDashboards.push("c.saikudash");
    expect(recentDashboards.all()).toEqual(["c.saikudash", "b.saikudash", "a.saikudash"]);
  });

  it("dedups: re-pushing an existing path moves it to the front", () => {
    recentDashboards.push("a.saikudash");
    recentDashboards.push("b.saikudash");
    recentDashboards.push("a.saikudash");
    expect(recentDashboards.all()).toEqual(["a.saikudash", "b.saikudash"]);
  });

  it("caps at RECENTS_CAP entries", () => {
    for (let i = 0; i < RECENTS_CAP + 5; i++) {
      recentDashboards.push(`d${i}.saikudash`);
    }
    expect(recentDashboards.all()).toHaveLength(RECENTS_CAP);
    // Most recent push is at the front
    expect(recentDashboards.all()[0]).toBe(`d${RECENTS_CAP + 4}.saikudash`);
  });

  it("ignores empty paths", () => {
    recentDashboards.push("");
    expect(recentDashboards.all()).toEqual([]);
  });

  it("no-ops when there is no current user", () => {
    currentUsername = null;
    recentDashboards.push("a.saikudash");
    expect(recentDashboards.all()).toEqual([]);
  });

  it("isolates recents by username (switching user starts fresh)", () => {
    recentDashboards.push("alice-1.saikudash");
    currentUsername = "bob";
    expect(recentDashboards.all()).toEqual([]);
    recentDashboards.push("bob-1.saikudash");
    expect(recentDashboards.all()).toEqual(["bob-1.saikudash"]);
    currentUsername = "alice";
    expect(recentDashboards.all()).toEqual(["alice-1.saikudash"]);
  });

  it("remove() drops a single path", () => {
    recentDashboards.push("a.saikudash");
    recentDashboards.push("b.saikudash");
    recentDashboards.remove("a.saikudash");
    expect(recentDashboards.all()).toEqual(["b.saikudash"]);
  });

  it("remove() is a no-op for an unknown path", () => {
    recentDashboards.push("a.saikudash");
    recentDashboards.remove("never-pushed.saikudash");
    expect(recentDashboards.all()).toEqual(["a.saikudash"]);
  });

  it("clear() drops everything", () => {
    recentDashboards.push("a.saikudash");
    recentDashboards.push("b.saikudash");
    recentDashboards.clear();
    expect(recentDashboards.all()).toEqual([]);
  });
});
