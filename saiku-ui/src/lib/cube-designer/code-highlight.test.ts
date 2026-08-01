import { describe, it, expect } from "vitest";
import { highlightXml, highlightYaml, escapeBasic } from "./code-highlight";

/**
 * Strip the highlighter's own `<span class="...">` wrappers to recover the
 * text a user actually sees. If the highlighter is correct, this equals the
 * HTML-escaped source — no `class="..."` or stray `>` leaking into the text.
 */
function visibleText(html: string): string {
  return html.replace(/<span class="[\w-]+">/g, "").replace(/<\/span>/g, "");
}

describe("highlightXml", () => {
  it("does NOT leak span markup into an attribute (the reported bug)", () => {
    const src = '<Attribute name="product_name" keyColumn="product_name" />';
    const html = highlightXml(src);
    // The exact garbage from the bug report must not appear.
    expect(html).not.toContain("name=class=");
    expect(html).not.toContain('=class="xml-string"');
    // Every emitted span is a clean, well-formed open tag.
    expect(html).not.toMatch(/<span\s+<span/);
    // What the user sees is just the escaped XML — no highlighter internals.
    expect(visibleText(html)).toBe(escapeBasic(src));
  });

  it("wraps tag names, attribute names and quoted values exactly once", () => {
    const html = highlightXml('<Dimension key="k">');
    expect(html).toContain('<span class="xml-tag">Dimension</span>');
    expect(html).toContain('<span class="xml-attr">key</span>');
    expect(html).toContain('<span class="xml-string">"k"</span>');
    // `class="xml-attr"` inside our own span must not get re-wrapped.
    expect(html).not.toContain('<span class="xml-string">"xml-attr"</span>');
    expect(html).not.toContain('<span class="xml-string">"xml-tag"</span>');
  });

  it("highlights comments without touching their inner text", () => {
    const html = highlightXml("<!-- No key: set a PK -->");
    expect(html).toContain('<span class="xml-comment">');
    expect(visibleText(html)).toBe(escapeBasic("<!-- No key: set a PK -->"));
  });

  it("handles a full multi-attribute element cleanly", () => {
    const src =
      '<Table name="product" schema="public" keyColumn="product_id" />';
    const html = highlightXml(src);
    expect(visibleText(html)).toBe(escapeBasic(src));
    expect(html).not.toContain("class=class");
  });
});

describe("highlightYaml", () => {
  it("does not let the key span collide with quoted values", () => {
    const src = 'name: "product_name"';
    const html = highlightYaml(src);
    expect(html).toContain('<span class="xml-tag">name</span>:');
    expect(html).toContain('<span class="xml-string">"product_name"</span>');
    // The key span's own `class="xml-tag"` must not be re-wrapped as a value.
    expect(html).not.toContain('<span class="xml-string">"xml-tag"</span>');
    expect(visibleText(html)).toBe(src);
  });

  it("handles list items and nested keys per line", () => {
    const src = '  - source: "Store"';
    const html = highlightYaml(src);
    expect(html).toContain('<span class="xml-tag">source</span>:');
    expect(html).toContain('<span class="xml-string">"Store"</span>');
    expect(visibleText(html)).toBe(src);
  });
});
