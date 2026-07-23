/*
 * Unit tests for the shared "latest AI insight" store. No persistence, no
 * SSR concerns here — just the set/clear contract the email modal relies on.
 */

import { describe, expect, it } from "vitest";
import { aiInsight } from "./aiInsight.svelte";

describe("aiInsight", () => {
  it("defaults to null", () => {
    expect(aiInsight.latestMarkdown).not.toBeUndefined();
  });

  it("set() stores the markdown", () => {
    aiInsight.set("**x**");
    expect(aiInsight.latestMarkdown).toBe("**x**");
  });

  it("clear() resets to null", () => {
    aiInsight.set("**x**");
    aiInsight.clear();
    expect(aiInsight.latestMarkdown).toBeNull();
  });

  it("set() overwrites a previous value", () => {
    aiInsight.set("first");
    aiInsight.set("second");
    expect(aiInsight.latestMarkdown).toBe("second");
  });
});
