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
});
