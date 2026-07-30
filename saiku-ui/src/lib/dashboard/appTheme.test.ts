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

  test("themeVars ignores non-colour primary values (no injection via token)", () => {
    const vars = themeVars({ mode: "light", primary: "url(evil)" as string });
    expect(vars["--saiku-app-primary"]).toBeUndefined();
  });
});
