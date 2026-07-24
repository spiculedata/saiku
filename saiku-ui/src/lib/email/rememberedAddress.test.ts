/*
 * Unit tests for rememberedAddress.ts.
 *
 * We stub localStorage with an in-memory Map so the tests don't need jsdom
 * and stay deterministic across the module-resetting harness (mirrors
 * measuresHiddenToggle.svelte.test.ts's fake-storage pattern).
 */

import { beforeEach, describe, expect, it, vi } from "vitest";

function installFakeLocalStorage(): Map<string, string> {
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
  vi.stubGlobal("localStorage", fakeStorage);
  return store;
}

type Mod = typeof import("./rememberedAddress");

let mod: Mod;
let backing: Map<string, string>;

async function loadFreshModule(): Promise<void> {
  vi.resetModules();
  mod = await import("./rememberedAddress");
}

beforeEach(async () => {
  backing = installFakeLocalStorage();
  await loadFreshModule();
});

describe("rememberedAddress", () => {
  it("returns '' when unset", () => {
    expect(mod.rememberedAddress.get()).toBe("");
  });

  it("set/get round-trips through localStorage", () => {
    mod.rememberedAddress.set("jane@example.com");
    expect(mod.rememberedAddress.get()).toBe("jane@example.com");
    expect(backing.get(mod.REMEMBERED_ADDRESS_STORAGE_KEY)).toBe("jane@example.com");
  });

  it("overwrites a previously remembered address", () => {
    mod.rememberedAddress.set("first@example.com");
    mod.rememberedAddress.set("second@example.com");
    expect(mod.rememberedAddress.get()).toBe("second@example.com");
  });

  it("a fresh module load reads the persisted value back", async () => {
    mod.rememberedAddress.set("persisted@example.com");
    await loadFreshModule();
    expect(mod.rememberedAddress.get()).toBe("persisted@example.com");
  });

  it("is SSR-safe: no throw when localStorage is undefined", async () => {
    vi.stubGlobal("localStorage", undefined);
    await loadFreshModule();
    expect(() => mod.rememberedAddress.get()).not.toThrow();
    expect(mod.rememberedAddress.get()).toBe("");
    expect(() => mod.rememberedAddress.set("x@example.com")).not.toThrow();
  });

  it("survives a localStorage outage gracefully (no throw)", () => {
    vi.stubGlobal("localStorage", {
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
    expect(() => mod.rememberedAddress.set("x@example.com")).not.toThrow();
    expect(mod.rememberedAddress.get()).toBe("");
  });
});
