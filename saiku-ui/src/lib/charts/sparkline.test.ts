import { describe, it, expect } from "vitest";
import { buildSparklineSvg, escapeHtml } from "$lib/charts/sparkline";

describe("escapeHtml", () => {
  it("escapes the five HTML-significant characters", () => {
    expect(escapeHtml(`<img src=x onerror="alert(1)">&'`)).toBe(
      "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&amp;&#39;",
    );
  });
});

describe("buildSparklineSvg", () => {
  it("emits a single <svg> with a polyline for a normal series", () => {
    const svg = buildSparklineSvg([1, 2, 3, 4]);
    expect(svg.startsWith("<svg")).toBe(true);
    expect(svg.endsWith("</svg>")).toBe(true);
    expect((svg.match(/<svg/g) ?? []).length).toBe(1);
    expect(svg).toContain("<polyline");
    expect(svg).toContain('points="');
  });

  it("returns empty string for null / empty / single-point series", () => {
     
    expect(buildSparklineSvg(null as any)).toBe("");
    expect(buildSparklineSvg([])).toBe("");
    expect(buildSparklineSvg([5])).toBe("");
    // a lone finite point among gaps is still < 2 finite points
    expect(buildSparklineSvg([null, 5, null])).toBe("");
  });

  it("treats null / NaN / Infinity entries as gaps but still draws the rest", () => {
    const svg = buildSparklineSvg([1, null, 3, NaN, 5, Infinity]);
    expect(svg).toContain("<polyline");
    // 3 finite points -> 3 coordinate pairs in the polyline
    const points = /points="([^"]*)"/.exec(svg)?.[1] ?? "";
    expect(points.trim().split(/\s+/).length).toBe(3);
  });

  it("normalises geometry into the viewBox (min at bottom, max at top)", () => {
    const svg = buildSparklineSvg([0, 10], { width: 100, height: 20 });
    expect(svg).toContain('viewBox="0 0 100 20"');
    const points = /points="([^"]*)"/.exec(svg)?.[1] ?? "";
    const [p1, p2] = points.trim().split(/\s+/);
    const y1 = Number(p1.split(",")[1]); // min -> larger y (lower on screen)
    const y2 = Number(p2.split(",")[1]); // max -> smaller y (higher on screen)
    expect(y1).toBeGreaterThan(y2);
  });

  it("draws a flat series on the vertical centre", () => {
    const svg = buildSparklineSvg([7, 7, 7], { height: 28 });
    const points = /points="([^"]*)"/.exec(svg)?.[1] ?? "";
    const ys = points
      .trim()
      .split(/\s+/)
      .map((p) => Number(p.split(",")[1]));
    expect(new Set(ys).size).toBe(1); // all same y
    expect(ys[0]).toBeCloseTo(14, 1); // centre of 28
  });

  it("places a highlight dot only on the hovered finite point", () => {
    expect(buildSparklineSvg([1, 2, 3], { highlightIndex: 1 })).toContain("<circle");
    // out-of-range index -> no dot
    expect(buildSparklineSvg([1, 2, 3], { highlightIndex: 9 })).not.toContain("<circle");
    // index points at a gap -> no dot
    expect(buildSparklineSvg([1, null, 3], { highlightIndex: 1 })).not.toContain("<circle");
  });

  it("SECURITY: never emits an attacker-controlled colour; falls back to grey", () => {
    const svg = buildSparklineSvg([1, 2, 3], { color: '"><script>alert(1)</script>' });
    expect(svg).not.toContain("<script");
    expect(svg).not.toContain("alert(1)");
    expect(svg).toContain('stroke="#888"');
  });

  it("SECURITY: accepts only safe colour shapes", () => {
    expect(buildSparklineSvg([1, 2], { color: "#1a2b3c" })).toContain('stroke="#1a2b3c"');
    expect(buildSparklineSvg([1, 2], { color: "rgba(10, 20, 30, 0.5)" })).toContain(
      'stroke="rgba(10, 20, 30, 0.5)"',
    );
    expect(buildSparklineSvg([1, 2], { color: "tomato" })).toContain('stroke="tomato"');
    // url(javascript:...) is rejected
    expect(buildSparklineSvg([1, 2], { color: "url(javascript:alert(1))" })).toContain(
      'stroke="#888"',
    );
  });

  it("SECURITY: HTML-escapes a hostile title before inserting it", () => {
    const svg = buildSparklineSvg([1, 2, 3], {
      title: `<img src=x onerror="alert(document.cookie)">`,
    });
    expect(svg).toContain("<title>");
    // The hostile markup is neutralised: no real <img> element, angle brackets
    // and the attribute quotes are entity-escaped so nothing can break out.
    expect(svg).not.toContain("<img");
    expect(svg).not.toContain('onerror="alert');
    expect(svg).toContain("&lt;img");
    expect(svg).toContain("&quot;");
  });

  it("respects custom width / height", () => {
    const svg = buildSparklineSvg([1, 5, 2], { width: 200, height: 40 });
    expect(svg).toContain('width="200"');
    expect(svg).toContain('height="40"');
    expect(svg).toContain('viewBox="0 0 200 40"');
  });
});
