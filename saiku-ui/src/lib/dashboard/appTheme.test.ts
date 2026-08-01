import { describe, expect, test } from "vitest";
import { themeVars, resolveFont, FONT_ALLOWLIST } from "./appTheme";

describe("appTheme", () => {
  test("themeVars maps tokens to --saiku-app-* custom properties", () => {
    const vars = themeVars({ mode: "light", primary: "#2f5d3a", accent: "#e2725b" });
    expect(vars["--saiku-app-primary"]).toBe("#2f5d3a");
    expect(vars["--saiku-app-accent"]).toBe("#e2725b");
  });

  test("resolveFont only returns an allowlisted stack; unknown → default", () => {
    const first = FONT_ALLOWLIST[0].key;
    expect(resolveFont(first)).toBe(FONT_ALLOWLIST[0].stack);
    expect(resolveFont("../../evil")).toBe(FONT_ALLOWLIST[0].stack);
  });

  test("themeVars rejects a non-colour primary — no injection, safe fallback", () => {
    const vars = themeVars({ mode: "light", primary: "url(evil)" as string });
    // The evil value never lands in any serialised var…
    for (const v of Object.values(vars)) expect(v).not.toContain("url(evil)");
    // …and --saiku-app-primary falls back to a safe hex (the resolved accent).
    expect(vars["--saiku-app-primary"]).toMatch(/^#[0-9a-f]{6}$/i);
  });

  test("themeVars serialises the full token set with defaults", () => {
    const vars = themeVars({ mode: "light" });
    for (const k of [
      "--saiku-app-ground",
      "--saiku-app-surface",
      "--saiku-app-fg",
      "--saiku-app-muted",
      "--saiku-app-accent",
      "--saiku-app-accent-2",
      "--saiku-app-radius",
      "--saiku-app-shadow",
      "--saiku-app-pad",
      "--saiku-app-font-display",
      "--saiku-app-font-body",
    ]) {
      expect(vars[k], k).toBeDefined();
    }
  });

  test("a named preset drives the serialised colours", () => {
    const vars = themeVars({ mode: "light", preset: "editorial" });
    expect(vars["--saiku-app-ground"]).toBe("#f2eee4");
    expect(vars["--saiku-app-accent"]).toBe("#2e5e43");
    expect(vars["--saiku-app-accent-2"]).toBe("#c85a3a");
  });

  test("explicit token overrides win over the preset", () => {
    const vars = themeVars({ mode: "light", preset: "editorial", accent: "#123456" });
    expect(vars["--saiku-app-accent"]).toBe("#123456");
    // untouched tokens still come from the preset
    expect(vars["--saiku-app-ground"]).toBe("#f2eee4");
  });
});
