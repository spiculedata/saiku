/*
 * Unit tests for the App Builder shell helpers — the theme-var serialisation,
 * nav choice, active-page fallback, and (critically) the custom-CSS scoping
 * path that AppShell relies on.
 */

import { describe, test, expect } from "vitest";
import { emptyApp, type SaikuApp } from "$lib/api/apps";
import {
  styleVarsToString,
  themeVarsStyle,
  appScopeId,
  rootSelectorFor,
  scopedCustomCss,
  navPosition,
  isRailNav,
  resolveActivePageId,
} from "$lib/views/app/appShell";

function app(patch: Partial<SaikuApp> = {}): SaikuApp {
  return { ...emptyApp("Test"), ...patch };
}

describe("styleVarsToString", () => {
  test("serialises a var map into a k:v; style string", () => {
    expect(styleVarsToString({ "--a": "1", "--b": "red" })).toBe("--a:1;--b:red;");
  });

  test("empty map serialises to empty string", () => {
    expect(styleVarsToString({})).toBe("");
  });
});

describe("themeVarsStyle", () => {
  test("only valid theme colours reach the inline style", () => {
    const style = themeVarsStyle(app({ theme: { mode: "light", primary: "#ff0000", accent: "nope" } }));
    expect(style).toContain("--saiku-app-primary:#ff0000;");
    // An invalid accent value never lands as the exact --saiku-app-accent var
    // (the trailing colon distinguishes it from --saiku-app-accent-soft/-2).
    expect(style).not.toContain("--saiku-app-accent:nope");
    expect(style).not.toContain("--saiku-app-accent:;");
    // font always resolves (allowlist default)
    expect(style).toContain("--saiku-app-font:");
  });
});

describe("appScopeId / rootSelectorFor", () => {
  test("empty id falls back to 'preview'", () => {
    expect(appScopeId(app({ id: "" }))).toBe("preview");
    expect(rootSelectorFor(app({ id: "" }))).toBe('[data-saiku-app="preview"]');
  });

  test("durable id is used verbatim", () => {
    expect(rootSelectorFor(app({ id: "sales" }))).toBe('[data-saiku-app="sales"]');
  });
});

describe("scopedCustomCss (security path)", () => {
  test("scopes author rules under the app root selector", () => {
    const out = scopedCustomCss(app({ id: "sales", theme: { mode: "light", customCss: ".x { color: red }" } }));
    expect(out).toContain('[data-saiku-app="sales"]');
    expect(out).toContain(".x");
  });

  test("fails closed on unparseable CSS", () => {
    const out = scopedCustomCss(app({ theme: { mode: "light", customCss: "this is { not ; valid ) css {{{" } }));
    expect(out).toBe("");
  });

  test("strips hostile declarations (position: fixed)", () => {
    const out = scopedCustomCss(app({ theme: { mode: "light", customCss: ".x { position: fixed; color: red }" } }));
    expect(out).not.toContain("fixed");
    expect(out).toContain("color");
  });

  test("no custom CSS yields empty string", () => {
    expect(scopedCustomCss(app())).toBe("");
  });
});

describe("navPosition / isRailNav", () => {
  test("defaults to rail", () => {
    expect(navPosition(app())).toBe("rail");
    expect(isRailNav(app())).toBe(true);
  });

  test("top position selects the top nav", () => {
    const a = app({ nav: { position: "top" } });
    expect(navPosition(a)).toBe("top");
    expect(isRailNav(a)).toBe(false);
  });
});

describe("resolveActivePageId", () => {
  test("honours a store id that points at a real page", () => {
    const a = app();
    const id = a.pages[0].id;
    expect(resolveActivePageId(a, id)).toBe(id);
  });

  test("falls back to page 0 when the store id is stale or null", () => {
    const a = app();
    expect(resolveActivePageId(a, "gone")).toBe(a.pages[0].id);
    expect(resolveActivePageId(a, null)).toBe(a.pages[0].id);
  });

  test("null when the app has no pages", () => {
    expect(resolveActivePageId(app({ pages: [] }), null)).toBeNull();
  });
});
