/**
 * The "Ask the AI" (Sparkles) toolbar button is shown DISABLED with a tooltip
 * when the LLM provider isn't configured, rather than hidden — so the
 * env-configured feature is discoverable. Configuration stays ops/env-only;
 * this button exposes no config surface.
 *
 * WorkspaceToolbar has 26 store imports, so a full SSR mount is impractical.
 * Pin the source contract instead (same pattern as WorkspaceToolbarEmailExport
 * / SaveQueryModal source-assertions):
 *  - the Ask AI button is ALWAYS rendered (no `{#if onAskAi}` hide gate on it),
 *  - it is `disabled={!aiConfigured}`,
 *  - the tooltip flips to the "AI isn't configured" copy when disabled,
 *  - it still opens the drawer (onAskAi) when enabled.
 * Plus: the i18n key backing the disabled tooltip exists.
 */
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import enJson from "$lib/i18n/en.json";

const TOOLBAR = readFileSync(
  fileURLToPath(new URL("./WorkspaceToolbar.svelte", import.meta.url)),
  "utf8",
);
const WORKSPACE = readFileSync(
  fileURLToPath(new URL("./Workspace.svelte", import.meta.url)),
  "utf8",
);

/** The Ask-AI button markup: the `tb-btn tb-btn--ai` button and its attributes. */
function aiButtonBlock(src: string): string {
  const start = src.indexOf('class="tb-btn tb-btn--ai"');
  expect(start, "Ask-AI button must exist").toBeGreaterThanOrEqual(0);
  const end = src.indexOf("</button>", start);
  expect(end).toBeGreaterThan(start);
  return src.slice(start - 40, end);
}

describe("Ask AI button: shown disabled when unconfigured, not hidden", () => {
  it("is always rendered — no {#if onAskAi} hide gate wrapping the AI button", () => {
    // The old behavior wrapped the whole AI button group in `{#if onAskAi}`.
    // That gate is gone; the button renders unconditionally and gates via disabled.
    const block = aiButtonBlock(TOOLBAR);
    // The disabled binding is the gate now.
    expect(block).toMatch(/disabled=\{!aiConfigured\}/);
  });

  it("flips the tooltip to the 'AI isn't configured' copy when disabled", () => {
    const block = aiButtonBlock(TOOLBAR);
    expect(block).toContain("workspace.aiQuery.disabled");
    // Enabled still shows the open tooltip.
    expect(block).toContain("workspace.aiQuery.open");
    // Ternary keyed on aiConfigured selects between them.
    expect(block).toMatch(/aiConfigured \? i18n\.t\("workspace\.aiQuery\.open"\)/);
  });

  it("still opens the Ask drawer when enabled (behavior preserved)", () => {
    const block = aiButtonBlock(TOOLBAR);
    expect(block).toMatch(/onclick=\{\(\) => onAskAi\?\.\(\)\}/);
  });

  it("declares the aiConfigured prop", () => {
    expect(TOOLBAR).toMatch(/aiConfigured\??:\s*boolean/);
    expect(TOOLBAR).toMatch(/let \{ onAskAi, aiConfigured = false \}/);
  });

  it("Workspace passes onAskAi unconditionally + aiConfigured from health", () => {
    // Previously: onAskAi={aiAskHealth.configured ? (...) : undefined} — which hid the button.
    // Now: onAskAi is always the drawer-open callback and aiConfigured carries the gate.
    expect(WORKSPACE).toMatch(/onAskAi=\{\(\) => \(aiDrawerOpen = true\)\}/);
    expect(WORKSPACE).toMatch(/aiConfigured=\{aiAskHealth\.configured\}/);
    // The old hide-via-undefined wiring is gone.
    expect(WORKSPACE).not.toContain("aiAskHealth.configured ? () => (aiDrawerOpen = true) : undefined");
  });

  it("has the disabled-tooltip i18n key", () => {
    const val = (enJson as Record<string, unknown>)["workspace.aiQuery.disabled"];
    expect(typeof val).toBe("string");
    expect((val as string).length).toBeGreaterThan(0);
  });
});
