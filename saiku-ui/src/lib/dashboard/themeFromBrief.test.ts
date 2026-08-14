import { describe, expect, test } from "vitest";
import { themeFromBrief } from "./themeFromBrief";

describe("themeFromBrief", () => {
  test("always returns a preset, even for a vague brief", () => {
    expect(themeFromBrief("").preset).toBeDefined();
    expect(themeFromBrief("something").preset).toBe("corporate");
  });

  test("maps vibe words to the closest preset", () => {
    expect(themeFromBrief("dark control-room ops look").preset).toBe(
      "dark-ops",
    );
    expect(themeFromBrief("clean minimal dashboard").preset).toBe("minimal");
    expect(themeFromBrief("warm editorial magazine feel").preset).toBe(
      "editorial",
    );
    expect(themeFromBrief("enterprise business BI").preset).toBe("corporate");
  });

  test("dark briefs set dark mode; others light", () => {
    expect(themeFromBrief("dark ops").mode).toBe("dark");
    expect(themeFromBrief("clean minimal").mode).toBe("light");
  });

  test("extracts an explicit hex accent", () => {
    expect(themeFromBrief("minimal, accent #ff8800").accent).toBe("#ff8800");
  });

  test("maps a named colour to an accent hex", () => {
    expect(themeFromBrief("editorial with a green accent").accent).toBe(
      "#2e7d55",
    );
    expect(themeFromBrief("corporate blue").accent).toBe("#2f6fed");
  });

  test("a second named colour becomes the brand-mark accent2", () => {
    const t = themeFromBrief(
      "editorial, green accent with terracotta brand mark",
    );
    expect(t.accent).toBe("#2e7d55");
    expect(t.accent2).toBe("#c85a3a");
    expect(t.accent2).not.toBe(t.accent);
  });

  test("type hints select fonts", () => {
    expect(themeFromBrief("serif editorial").fontDisplay).toBe("serif-1");
    expect(themeFromBrief("technical monospace").fontDisplay).toBe("mono-1");
  });

  /*
   * saiku#1763: "monospace" was applied to HEADINGS whatever noun it modified,
   * so a brief asking for monospace FIGURES set monospace headings and left the
   * figures in the body sans — the opposite of what was asked, and visible as
   * monospace page titles above proportional KPI numbers.
   */
  test("maps monospace FIGURES to the numerals token, not headings", () => {
    const t = themeFromBrief("clinical lab report, monospace figures");
    expect(t.numerals).toBe("mono");
    expect(t.fontDisplay).toBeUndefined();
  });

  test("accepts the other ways of saying figures", () => {
    for (const word of ["numbers", "numerals", "digits"]) {
      expect(themeFromBrief(`monospace ${word}`).numerals, word).toBe("mono");
    }
  });

  test("still maps an unqualified monospace to headings", () => {
    const t = themeFromBrief("technical monospace");
    expect(t.fontDisplay).toBe("mono-1");
    expect(t.numerals).toBeUndefined();
  });

  test("maps monospace BODY to the body token", () => {
    const t = themeFromBrief("monospace body text");
    expect(t.fontBody).toBe("mono-1");
    expect(t.fontDisplay).toBeUndefined();
  });

  test("reads hairline cards as a borderless-shadow look, not an elevated one", () => {
    expect(themeFromBrief("crisp hairline cards").shadow).toBe("none");
  });

  test("form hints select radius / shadow / density", () => {
    expect(themeFromBrief("rounded friendly cards").radius).toBe("xl");
    expect(themeFromBrief("sharp square crisp").radius).toBe("none");
    expect(themeFromBrief("flat borderless").shadow).toBe("none");
    expect(themeFromBrief("elevated floating cards").shadow).toBe("lg");
    expect(themeFromBrief("compact dense").density).toBe("compact");
    expect(themeFromBrief("airy spacious").density).toBe("comfortable");
  });

  test("never throws on odd input", () => {
    expect(() => themeFromBrief(undefined as unknown as string)).not.toThrow();
    expect(() => themeFromBrief("###")).not.toThrow();
  });
});
