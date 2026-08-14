/*
 * saiku#1760 — the assistant's greeting is applied to the message list.
 */

import { describe, expect, test } from "vitest";
import {
  withGreeting,
  DEFAULT_GREETING,
  type AssistantMessage,
} from "./appAssistant";

const USER: AssistantMessage = { role: "user", text: "How did sales track?" };
const REPLY: AssistantMessage = {
  role: "assistant",
  text: "Up 6%.",
  kind: "reply",
};

describe("withGreeting", () => {
  test("seeds the greeting into an empty list", () => {
    const out = withGreeting([], "Ask me about scripts.");
    expect(out).toEqual([
      { role: "assistant", text: "Ask me about scripts.", kind: "greeting" },
    ]);
  });

  test("falls back to the default when the app configures none", () => {
    expect(withGreeting([], undefined)[0].text).toBe(DEFAULT_GREETING);
  });

  test("replaces the greeting when the author edits it — the bug", () => {
    const seeded = withGreeting([], undefined);
    const edited = withGreeting(seeded, "I'm scoped to the Pharma Rx cube.");
    expect(edited).toHaveLength(1);
    expect(edited[0].text).toBe("I'm scoped to the Pharma Rx cube.");
    expect(edited[0].kind).toBe("greeting");
  });

  test("keeps conversation turns when the greeting changes mid-thread", () => {
    const withThread = [...withGreeting([], "old"), USER, REPLY];
    const edited = withGreeting(withThread, "new");
    expect(edited[0].text).toBe("new");
    expect(edited.slice(1)).toEqual([USER, REPLY]);
  });

  test("returns the same reference when nothing changed (no reactive churn)", () => {
    const seeded = withGreeting([], "same");
    expect(withGreeting(seeded, "same")).toBe(seeded);
  });

  test("does not add a second greeting to a list that already has one", () => {
    const twice = withGreeting(withGreeting([], "a"), "b");
    expect(twice.filter((m) => m.kind === "greeting")).toHaveLength(1);
  });
});
