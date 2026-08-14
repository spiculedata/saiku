/**
 * @vitest-environment jsdom
 */

/*
 * XSS regression for TextTile. The component renders tile.text via
 * DOMPurify.sanitize before {@html} insertion; this test asserts the
 * sanitiser strips the OWASP common XSS vectors so an analyst with
 * write access to the JCR repository can't escalate to script
 * execution in another viewer's session.
 *
 * Threat model: malicious analyst on a shared JCR; surface = a TextTile's
 * tile.text. We don't test DOMPurify itself (assumed-correct), we test
 * that the *configuration* we use (USE_PROFILES: { html: true }) strips
 * the vectors we care about.
 *
 * Test env: jsdom — vitest's `node` default leaves DOMPurify in
 * fall-through mode (returns input unchanged), defeating the test. The
 * @vitest-environment pragma above flips the env per-file without
 * touching the global vite.config.
 *
 * jsdom rather than happy-dom, deliberately: happy-dom (every version up
 * to 20.10.6) strips <h2> from DOMPurify 3.4.12's output while jsdom and
 * real browsers keep it. That's a happy-dom DOM-fidelity gap, not a
 * sanitiser behaviour. It surfaced here as a false failure — but the same
 * gap could just as easily hide a false PASS, i.e. a payload happy-dom
 * neutralises that a browser executes. For a test whose entire purpose is
 * asserting what survives sanitisation, the environment has to model the
 * DOM faithfully or the result means nothing.
 */

import { describe, test, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import DOMPurify from "dompurify";
import { renderTinyMarkdown } from "$lib/api/tinyMarkdown";

// Mirror TextTile.svelte's SANITISE_CONFIG exactly — tests cover the
// real component contract, not a synthetic config.
function sanitise(input: string): string {
  return DOMPurify.sanitize(input, {
    FORBID_TAGS: ["style", "embed", "object", "iframe", "form"],
  });
}

// Mirror TextTile.svelte's full render pipeline: markdown -> HTML via
// renderTinyMarkdown, then DOMPurify. tile.text is stored as markdown.
function render(input: string): string {
  return sanitise(renderTinyMarkdown(input));
}

describe("TextTile DOMPurify config", () => {
  test("strips raw <script> tags", () => {
    const out = sanitise("hello <script>alert(1)</script> world");
    expect(out).not.toContain("<script");
    expect(out).not.toContain("alert(1)");
  });

  test("strips on-event attribute handlers", () => {
    const out = sanitise('<img src=x onerror="alert(1)">');
    expect(out).not.toContain("onerror");
    expect(out).not.toContain("alert");
  });

  test("strips javascript: URLs in href", () => {
    const out = sanitise('<a href="javascript:alert(1)">click</a>');
    expect(out).not.toMatch(/href=["']?javascript:/i);
  });

  test("strips javascript: URLs in src", () => {
    const out = sanitise('<iframe src="javascript:alert(1)"></iframe>');
    expect(out).not.toMatch(/javascript:/i);
  });

  test("strips data: URLs that carry HTML", () => {
    const out = sanitise('<a href="data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==">x</a>');
    expect(out).not.toMatch(/href=["']?data:text\/html/i);
  });

  test("keeps safe text + heading markup intact", () => {
    const out = sanitise("<h2>Q4 review</h2><p>Up <strong>14%</strong> YoY.</p>");
    expect(out).toContain("<h2>Q4 review</h2>");
    expect(out).toContain("<strong>14%</strong>");
  });

  test("strips <style> blocks (CSS-based attacks)", () => {
    const out = sanitise("<style>body{background:url('javascript:alert(1)')}</style>hi");
    expect(out).not.toContain("<style");
    expect(out.toLowerCase()).toContain("hi");
  });

  test("strips <object> tag", () => {
    // <embed> + <svg> namespace handling differs between happy-dom and
    // real browsers — verified in browser smoke tests rather than here.
    // The on-event attribute strip is covered by the onerror case above
    // (which DOMPurify treats more aggressively because img+onerror is
    // a known-fired vector).
    const out = sanitise('<object data="x">malicious</object>safe');
    expect(out).not.toContain("<object");
    expect(out).toContain("safe");
  });
});

describe("TextTile markdown rendering (#1602)", () => {
  test("renders a heading instead of showing the literal '#'", () => {
    const out = render("# Sales report");
    expect(out).toMatch(/<h[1-6]>Sales report<\/h[1-6]>/);
    expect(out).not.toContain("# Sales report");
  });

  test("renders **bold** as <strong>, not literal asterisks", () => {
    const out = render("**Login:** demo");
    expect(out).toContain("<strong>Login:</strong>");
    expect(out).not.toContain("**Login:**");
  });

  test("renders `inline code` as <code>, not literal backticks", () => {
    const out = render("Use the `admin` account");
    expect(out).toContain("<code>admin</code>");
    expect(out).not.toContain("`admin`");
  });

  test("renders bullet lists", () => {
    const out = render("- first\n- second");
    expect(out).toContain("<ul>");
    expect(out).toContain("<li>first</li>");
    expect(out).toContain("<li>second</li>");
  });

  test("markdown pipeline still neutralises embedded XSS", () => {
    const out = render("# Hi\n\n<script>alert(1)</script>");
    // The renderer escapes source before emitting tags, so the payload
    // survives only as inert, escaped text — never as a live <script> tag.
    expect(out).not.toMatch(/<script/i);
    expect(out).toContain("&lt;script&gt;");
  });
});

/*
 * saiku#1787 — a TextTile inside an App Builder shell painted its body from
 * `hsl(var(--fg))`, the SAIKU UI CHROME foreground. The chrome around an app is
 * dark while the app surface is light, so --fg resolved to the dark-theme
 * foreground (#fcfcfd) and was painted onto the app's white card: a 1.02:1
 * contrast ratio, i.e. invisible text with no other symptom.
 *
 * Every other tile paints from the app-scoped `--saiku-app-*` tokens that
 * appSkin.ts emits, which is why only this tile was hit. The token must lead,
 * with the chrome token as the fallback for a TextTile on a plain dashboard
 * (outside any app shell), where --saiku-app-fg is simply unset.
 */
describe("TextTile theming (saiku#1787)", () => {
  // Resolved from the vitest cwd (saiku-ui root) rather than import.meta.url:
  // this file runs under @vitest-environment jsdom, where import.meta.url is not
  // a file: URL and fileURLToPath() throws.
  const src = readFileSync(
    resolve(process.cwd(), "src/lib/views/dashboard/tiles/TextTile.svelte"),
    "utf8",
  );
  const styleBlock = src.match(/<style[^>]*>([\s\S]*?)<\/style>/)?.[1] ?? "";

  test("has a .text-tile colour declaration at all", () => {
    // Guard: if the rule is renamed away, the assertions below would pass vacuously.
    expect(styleBlock).toMatch(/\.text-tile\s*\{[^}]*color:/);
  });

  test("body colour leads with the app theme token, not the chrome token", () => {
    const rule = styleBlock.match(/\.text-tile\s*\{[^}]*\}/)?.[0] ?? "";
    const colour = rule.match(/color:\s*([^;]+);/)?.[1]?.trim() ?? "";

    expect(
      colour.startsWith("var(--saiku-app-fg"),
      `.text-tile paints from "${colour}" — inside an app shell the chrome ` +
        `--fg is the wrong ground and renders near-invisible. Lead with ` +
        `var(--saiku-app-fg, …).`,
    ).toBe(true);

    // …and still degrade to the chrome token on a plain dashboard.
    expect(colour).toContain("--fg");
  });

  /*
   * The body colour alone isn't enough: app.css declares a global
   * `h1…h6 { color: hsl(var(--fg)) }`, and an explicit colour on the heading
   * beats the colour inherited from .text-tile — so a `### Heading` stayed
   * chrome-white on the app's white card even after the body was fixed.
   * The tile's heading reset must therefore re-inherit, and must span every
   * level renderTinyMarkdown can emit (it maps # … #### onto h3…h6).
   */
  test("heading reset covers every level tinyMarkdown emits (h3–h6)", () => {
    const selectors =
      styleBlock.match(/((?:\s*\.text-tile\s*:global\(h[1-6]\)\s*,?)+)\s*\{/)?.[1] ?? "";
    for (const level of ["h3", "h4", "h5", "h6"]) {
      expect(
        selectors,
        `.text-tile heading reset does not cover ${level} — renderTinyMarkdown ` +
          `emits it, and the global app.css h1…h6 colour would win`,
      ).toContain(`:global(${level})`);
    }
  });

  test("headings re-inherit the tile's themed colour", () => {
    const headingRule =
      styleBlock.match(/(?:\s*\.text-tile\s*:global\(h[1-6]\)\s*,?)+\s*\{([^}]*)\}/)?.[1] ?? "";
    expect(
      /color:\s*inherit/.test(headingRule),
      "the .text-tile heading reset must set color: inherit, or app.css's global " +
        "h1…h6 rule repaints headings in the Saiku chrome foreground",
    ).toBe(true);
  });
});
