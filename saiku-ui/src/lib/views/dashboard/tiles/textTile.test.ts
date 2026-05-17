/**
 * @vitest-environment happy-dom
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
 * Test env: happy-dom — vitest's `node` default leaves DOMPurify in
 * fall-through mode (returns input unchanged), defeating the test. The
 * @vitest-environment pragma above flips the env per-file without
 * touching the global vite.config.
 */

import { describe, test, expect } from "vitest";
import DOMPurify from "dompurify";

// Mirror TextTile.svelte's SANITISE_CONFIG exactly — tests cover the
// real component contract, not a synthetic config.
function sanitise(input: string): string {
  return DOMPurify.sanitize(input, {
    FORBID_TAGS: ["style", "embed", "object", "iframe", "form"],
  });
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
