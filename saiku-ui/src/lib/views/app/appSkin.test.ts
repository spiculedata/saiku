/*
 * @vitest-environment jsdom
 *
 * Unit tests for the built-in App Builder skin.
 *
 * The one rule worth pinning is the chart-card header hide. It exists so a
 * chart tile doesn't render the generic tile header on top of the title the
 * ECharts option already draws — but the tile header is also where the
 * configure / menu / remove buttons live, so hiding it unconditionally makes
 * chart tiles unreachable from the authoring UI. The rule must therefore be
 * gated on the app root NOT carrying `data-saiku-app-edit` (which AppShell
 * stamps while editing).
 */

import { describe, test, expect } from "vitest";
import { appSkinCss } from "$lib/views/app/appSkin";

const SCOPE = '[data-saiku-app="preview"]';

/** The single declaration block that hides a chart card's generic header. */
function chartHeaderRule(css: string): string {
  const line = css.split("\n").find((l) => l.includes(".tile-header { display: none; }"));
  if (!line) throw new Error("chart-card header rule not found in skin CSS");
  return line;
}

describe("appSkinCss", () => {
  test("scopes every rule under the app root so it cannot leak", () => {
    const css = appSkinCss(SCOPE);
    const selectors = css
      .split("\n")
      .filter((l) => l.includes("{") && !l.trimStart().startsWith("/*"))
      .map((l) => l.slice(0, l.indexOf("{")).trim());

    expect(selectors.length).toBeGreaterThan(0);
    for (const sel of selectors) {
      for (const part of sel.split(",")) {
        expect(part.trim().startsWith(SCOPE)).toBe(true);
      }
    }
  });

  test("hides the chart card's generic header only outside edit mode", () => {
    const rule = chartHeaderRule(appSkinCss(SCOPE));
    expect(rule).toContain(`${SCOPE}:not([data-saiku-app-edit])`);
  });

  test("chart-card header rule does not match an app root marked as editing", () => {
    const rule = chartHeaderRule(appSkinCss(SCOPE));
    const selector = rule.slice(0, rule.indexOf("{")).trim();

    const editing = document.createElement("div");
    editing.setAttribute("data-saiku-app", "preview");
    editing.setAttribute("data-saiku-app-edit", "");
    editing.innerHTML = '<div class="tile"><div class="tile-header"></div></div>';

    const viewing = document.createElement("div");
    viewing.setAttribute("data-saiku-app", "preview");
    viewing.innerHTML = '<div class="tile"><div class="tile-header"></div></div>';

    // Attached so :not()/:has() resolve against a real tree. Matching runs from
    // the document, because the selector's leading compound is the app root
    // itself — an element-scoped querySelector only ever looks at descendants.
    document.body.append(editing, viewing);
    try {
      const matched = [...document.querySelectorAll(selector)];
      expect(matched).toHaveLength(1);
      expect(viewing.contains(matched[0])).toBe(true);
      expect(editing.contains(matched[0])).toBe(false);
    } finally {
      editing.remove();
      viewing.remove();
    }
  });
});
