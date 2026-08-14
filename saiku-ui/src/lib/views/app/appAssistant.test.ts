/*
 * saiku#1760 — the assistant's greeting is applied to the message list.
 */

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
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

/*
 * saiku#1791 — a follow-up to #1761 ("FoodMart defaults leak into every app").
 * That pass fixed the panel title (`slot.title ?? appName ?? "this app"`) but
 * left the composer placeheld "Ask about sales, products, trends…", so an HR
 * app whose persona, scope note and greeting all talk about headcount still
 * invited questions about sales and products in the input directly beneath.
 *
 * Asserted against source: the placeholder is markup, and the repo has no
 * client-mount harness for the panel.
 */
describe("assistant composer placeholder is app-derived (saiku#1791)", () => {
  const src = readFileSync(
    resolve(process.cwd(), "src/lib/views/app/AppAssistant.svelte"),
    "utf8",
  );

  const placeholder =
    src.match(/class="assistant__input"[\s\S]*?placeholder=(\{[^}]*\}|"[^"]*")/)?.[1] ?? "";

  test("the composer declares a placeholder at all", () => {
    expect(placeholder, "assistant__input placeholder not found").not.toBe("");
  });

  test("is an expression, not a hard-coded string", () => {
    expect(
      placeholder.startsWith("{"),
      `composer placeholder is the literal ${placeholder} — derive it from the ` +
        `app (title / appName) so it can't describe a dataset the app isn't about`,
    ).toBe(true);
  });

  test("carries no sample-dataset vocabulary", () => {
    // The nouns that shipped with the FoodMart sample and read as wrong anywhere else.
    for (const word of ["sales", "products", "FoodMart", "stores", "warehouse"]) {
      expect(
        placeholder.toLowerCase(),
        `composer placeholder still names "${word}"`,
      ).not.toContain(word.toLowerCase());
    }
  });
});
