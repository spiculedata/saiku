import { describe, it, expect } from "vitest";
import {
  addServer,
  removeServer,
  setActive,
  activeServer,
  normalizeBaseUrl,
  type Registry,
} from "../src/lib/serverRegistry";
import type { ServerEntry } from "../src/lib/types";

const entry = (id: string): ServerEntry => ({
  id,
  label: `Server ${id}`,
  baseUrl: `https://${id}.example.com`,
  strategy: "basic",
});

const empty: Registry = { servers: [], activeId: null };

describe("addServer", () => {
  it("adds a server and sets it as active when registry is empty", () => {
    const r = addServer(empty, entry("a"));
    expect(r.servers).toHaveLength(1);
    expect(r.activeId).toBe("a");
  });

  it("keeps existing activeId when a second server is added", () => {
    const r1 = addServer(empty, entry("a"));
    const r2 = addServer(r1, entry("b"));
    expect(r2.servers).toHaveLength(2);
    expect(r2.activeId).toBe("a");
  });
});

describe("removeServer", () => {
  it("removes the active server and picks the next available", () => {
    const r1 = addServer(empty, entry("a"));
    const r2 = addServer(r1, entry("b"));
    const r3 = removeServer(r2, "a");
    expect(r3.servers).toHaveLength(1);
    expect(r3.activeId).toBe("b");
  });

  it("sets activeId to null when last server is removed", () => {
    const r1 = addServer(empty, entry("a"));
    const r2 = removeServer(r1, "a");
    expect(r2.servers).toHaveLength(0);
    expect(r2.activeId).toBeNull();
  });
});

describe("setActive", () => {
  it("sets activeId when server exists", () => {
    const r1 = addServer(empty, entry("a"));
    const r2 = addServer(r1, entry("b"));
    const r3 = setActive(r2, "b");
    expect(r3.activeId).toBe("b");
  });

  it("does not change registry when server id is unknown", () => {
    const r1 = addServer(empty, entry("a"));
    const r2 = setActive(r1, "unknown");
    expect(r2.activeId).toBe("a");
  });
});

describe("normalizeBaseUrl", () => {
  it("strips trailing slash", () => {
    expect(normalizeBaseUrl("https://example.com/")).toBe("https://example.com");
  });

  it("strips trailing /rest", () => {
    expect(normalizeBaseUrl("https://example.com/rest")).toBe("https://example.com");
  });

  it("strips trailing /rest/", () => {
    expect(normalizeBaseUrl("https://example.com/rest/")).toBe("https://example.com");
  });

  it("leaves clean URL unchanged", () => {
    expect(normalizeBaseUrl("https://example.com")).toBe("https://example.com");
  });
});
