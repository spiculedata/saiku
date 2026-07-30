import { describe, expect, test } from "vitest";
import { sanitiseAndScopeCss } from "./cssSanitiser";

const ROOT = '[data-saiku-app="app1"]';

describe("sanitiseAndScopeCss", () => {
  test("scopes every selector under the app root", () => {
    const out = sanitiseAndScopeCss(".card { color: red } h1 { margin: 0 }", ROOT);
    expect(out).toContain(`${ROOT} .card`);
    expect(out).toContain(`${ROOT} h1`);
    expect(out).not.toMatch(/^\s*\.card/m);
  });
  test("drops @import entirely", () => {
    const out = sanitiseAndScopeCss('@import url("//evil");\n.a{color:red}', ROOT);
    expect(out).not.toContain("@import");
    expect(out).toContain(`${ROOT} .a`);
  });
  test("strips remote url() but keeps data: and same-origin refs", () => {
    const out = sanitiseAndScopeCss('.a{background:url(https://evil/x.png)} .b{background:url(data:image/png;base64,AA)}', ROOT);
    expect(out).not.toContain("evil");
    expect(out).toContain("data:image/png");
  });
  test("removes position:fixed, expression(), behavior, -moz-binding", () => {
    const css = '.a{position:fixed} .b{width:expression(alert(1))} .c{behavior:url(x.htc)} .d{-moz-binding:url(x)}';
    const out = sanitiseAndScopeCss(css, ROOT);
    expect(out).not.toMatch(/position\s*:\s*fixed/i);
    expect(out).not.toMatch(/expression\s*\(/i);
    expect(out).not.toMatch(/behavior\s*:/i);
    expect(out).not.toMatch(/-moz-binding/i);
  });
  test("fails closed: unparseable CSS yields empty string", () => {
    expect(sanitiseAndScopeCss("this is { not ; valid ) css {{{", ROOT)).toBe("");
  });
  test("empty / nullish input yields empty string", () => {
    expect(sanitiseAndScopeCss("", ROOT)).toBe("");
    expect(sanitiseAndScopeCss(undefined as unknown as string, ROOT)).toBe("");
  });

  // Regression: remote url() hidden inside a CSS custom property, weaponised
  // via var() substitution. css-tree parses --* / var() args as Raw tokens
  // with no Url child, so the Url walk never saw them.
  test("strips remote url() hidden in a custom property (var weaponisation)", () => {
    const out = sanitiseAndScopeCss(
      ".a{--bg:url(https://evil/track.png)} .b{background:var(--bg)}",
      ROOT,
    );
    expect(out).not.toContain("evil");
    expect(out).not.toContain("https://");
  });
  test("strips remote url() hidden in a var() fallback", () => {
    const out = sanitiseAndScopeCss(".a{background:var(--x,url(https://evil))}", ROOT);
    expect(out).not.toContain("evil");
    expect(out).not.toContain("https://");
  });
  test("keeps data: and relative url() inside custom properties", () => {
    const out = sanitiseAndScopeCss(
      ".a{--ok:url(data:image/png;base64,AA)} .b{--rel:url(x.png)}",
      ROOT,
    );
    expect(out).toContain("data:image/png");
    expect(out).toContain("x.png");
  });

  // Regression: @keyframes stops (from/to/%) must not be scoped, or the whole
  // animation is invalid and browsers drop it.
  test("does not scope @keyframes stops", () => {
    const out = sanitiseAndScopeCss("@keyframes k{from{opacity:0}to{opacity:1}}", ROOT);
    expect(out).toContain("@keyframes k");
    expect(out).toMatch(/(^|[{;\s])from\s*\{/);
    expect(out).toMatch(/(^|[{;\s}])to\s*\{/);
    expect(out).not.toContain(`${ROOT} from`);
    expect(out).not.toContain(`${ROOT} to`);
  });

  // Regression: CSS escape-sequence bypass — \65 -> "e", \62 -> "b".
  test("drops escaped expression() bypass", () => {
    const out = sanitiseAndScopeCss(".a{width:\\65 xpression(alert(1))}", ROOT);
    expect(out).not.toContain("xpression");
    expect(out).not.toMatch(/expression\s*\(/i);
  });
  test("drops escaped behavior bypass", () => {
    const out = sanitiseAndScopeCss(".a{\\62 ehavior:url(x.htc)}", ROOT);
    expect(out).not.toContain("ehavior");
    expect(out).not.toMatch(/behavior\s*:/i);
  });

  // Minor polish: @page is dropped; nested :is()/:where() selectors are not
  // individually prefixed (only the top-level selector is scoped).
  test("drops @page", () => {
    const out = sanitiseAndScopeCss("@page{margin:0} .a{color:red}", ROOT);
    expect(out).not.toContain("@page");
    expect(out).toContain(`${ROOT} .a`);
  });
  test("scopes only the top-level selector, not those inside :is()", () => {
    const out = sanitiseAndScopeCss(".a:is(.b,.c){color:red}", ROOT);
    expect(out).toContain(`${ROOT} .a:is(.b,.c)`);
    expect(out).not.toContain(`:is(${ROOT}`);
  });
});
