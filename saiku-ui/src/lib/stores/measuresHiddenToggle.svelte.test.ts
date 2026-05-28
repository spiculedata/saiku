/*
 * Unit tests for the per-session "show hidden measures" toggle (#834).
 *
 * We stub sessionStorage with an in-memory Map so the tests don't need
 * jsdom and stay deterministic across the module-resetting harness.
 */

import { beforeEach, describe, expect, it, vi } from "vitest";

function installFakeSessionStorage(): Map<string, string> {
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
  vi.stubGlobal("sessionStorage", fakeStorage);
  return store;
}

type ToggleModule = typeof import("./measuresHiddenToggle.svelte");

let mod: ToggleModule;
let backing: Map<string, string>;

async function loadFreshModule(): Promise<void> {
  // The store reads sessionStorage at module-evaluation time to seed
  // its $state. Reset modules so each test gets a clean singleton.
  vi.resetModules();
  mod = await import("./measuresHiddenToggle.svelte");
}

beforeEach(async () => {
  backing = installFakeSessionStorage();
  await loadFreshModule();
});

describe("measuresHiddenToggle", () => {
  it("defaults to false when sessionStorage is empty", () => {
    expect(mod.measuresHiddenToggle.enabled).toBe(false);
  });

  it("set(true) flips state and persists '1' under the storage key", () => {
    mod.measuresHiddenToggle.set(true);
    expect(mod.measuresHiddenToggle.enabled).toBe(true);
    expect(backing.get(mod.MEASURES_HIDDEN_TOGGLE_STORAGE_KEY)).toBe("1");
  });

  it("set(false) drops the storage entry (instead of storing '0')", () => {
    mod.measuresHiddenToggle.set(true);
    mod.measuresHiddenToggle.set(false);
    expect(mod.measuresHiddenToggle.enabled).toBe(false);
    expect(backing.has(mod.MEASURES_HIDDEN_TOGGLE_STORAGE_KEY)).toBe(false);
  });

  it("set() is idempotent — re-setting same value doesn't rewrite", () => {
    mod.measuresHiddenToggle.set(true);
    backing.set(mod.MEASURES_HIDDEN_TOGGLE_STORAGE_KEY, "sentinel");
    mod.measuresHiddenToggle.set(true);
    // Sentinel survived because set() short-circuited.
    expect(backing.get(mod.MEASURES_HIDDEN_TOGGLE_STORAGE_KEY)).toBe("sentinel");
  });

  it("toggle() flips between true/false", () => {
    expect(mod.measuresHiddenToggle.enabled).toBe(false);
    mod.measuresHiddenToggle.toggle();
    expect(mod.measuresHiddenToggle.enabled).toBe(true);
    mod.measuresHiddenToggle.toggle();
    expect(mod.measuresHiddenToggle.enabled).toBe(false);
  });

  it("reads existing '1' on module load (survives reload)", async () => {
    backing.set("saiku:measures:includeHidden", "1");
    await loadFreshModule();
    expect(mod.measuresHiddenToggle.enabled).toBe(true);
  });

  it("treats values other than '1' as off", async () => {
    backing.set("saiku:measures:includeHidden", "true");
    await loadFreshModule();
    expect(mod.measuresHiddenToggle.enabled).toBe(false);
  });

  it("reset() clears state and the storage entry", () => {
    mod.measuresHiddenToggle.set(true);
    mod.measuresHiddenToggle.reset();
    expect(mod.measuresHiddenToggle.enabled).toBe(false);
    expect(backing.has(mod.MEASURES_HIDDEN_TOGGLE_STORAGE_KEY)).toBe(false);
  });

  it("survives a sessionStorage outage gracefully (no throw, no state change)", () => {
    vi.stubGlobal("sessionStorage", {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
      removeItem: () => {
        throw new Error("blocked");
      },
      clear: () => undefined,
      key: () => null,
      length: 0,
    } satisfies Storage);
    expect(() => mod.measuresHiddenToggle.set(true)).not.toThrow();
    // In-memory state still reflects the user's intent even if persistence failed.
    expect(mod.measuresHiddenToggle.enabled).toBe(true);
  });

  it("ignores missing sessionStorage (e.g. SSR) without throwing", async () => {
    // Drop sessionStorage entirely.
    vi.stubGlobal("sessionStorage", undefined);
    await loadFreshModule();
    expect(mod.measuresHiddenToggle.enabled).toBe(false);
    expect(() => mod.measuresHiddenToggle.set(true)).not.toThrow();
    expect(mod.measuresHiddenToggle.enabled).toBe(true);
  });
});
