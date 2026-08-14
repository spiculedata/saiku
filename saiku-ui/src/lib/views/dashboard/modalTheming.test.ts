/**
 * saiku#1794 — a chrome overlay rendered INSIDE an App Builder root must reset
 * both the ground and the ink.
 *
 * `appSkin.ts` paints the app shell with `.saiku-app { color: var(--saiku-app-fg, …) }`,
 * and the tile-editor / filter-suggestion modals mount inside that shell. Each one
 * re-established the chrome *background* (`background: hsl(var(--bg))`) but not the
 * chrome *foreground*, so every descendant without an explicit colour inherited the
 * APP's ink onto the modal's dark ground.
 *
 * Most of the modal was unaffected because `.field__label`, `.hint` and friends set
 * their own colour. What broke were the controls that deliberately inherit —
 * `label.checkbox` and `label.radio` — which rendered #1c2430 on a #16181d panel
 * (~1.1:1). *Detect anomalies*, *Show forecast*, *Emit cross-filter on brush* and the
 * *Saved query* radio all read as disabled controls, so authors skipped documented
 * features believing they were unavailable on their tile type.
 *
 * The lock is the pairing, not the symptom: a surface that claims the chrome
 * background must also claim the chrome foreground, or the next inheriting child
 * regresses the same way.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, it, expect } from "vitest";

const MODALS = ["TileEditorModal.svelte", "FilterSuggestionsModal.svelte"];

/** Extract the body of the first `.modal { … }` rule in a component's <style> block. */
function modalRule(file: string): string {
  const src = readFileSync(fileURLToPath(new URL(`./${file}`, import.meta.url)), "utf8");
  const styleBlock = src.match(/<style[^>]*>([\s\S]*?)<\/style>/)?.[1] ?? "";
  const rule = styleBlock.match(/(^|\n)\s*\.modal\s*\{([^}]*)\}/)?.[2];
  expect(rule, `${file}: no .modal { } rule found`).toBeDefined();
  return rule as string;
}

describe("modals rendered inside an app shell reset to chrome colours (saiku#1794)", () => {
  for (const file of MODALS) {
    it(`${file}: .modal claims the chrome background`, () => {
      // Guard — the foreground assertion below is only meaningful for a surface
      // that has actually opted out of the app's ground.
      expect(modalRule(file)).toMatch(/background:\s*hsl\(var\(--bg\)\)/);
    });

    it(`${file}: .modal also claims the chrome foreground`, () => {
      const rule = modalRule(file);
      expect(
        /(^|[;{\s])color:\s*hsl\(var\(--fg\)\)/.test(rule),
        `${file} sets the chrome background but not the chrome colour, so it ` +
          `inherits --saiku-app-fg from the surrounding .saiku-app shell. Any ` +
          `descendant that doesn't set its own colour (label.checkbox, label.radio) ` +
          `renders app-dark ink on the modal's dark panel.`,
      ).toBe(true);
    });
  }
});
