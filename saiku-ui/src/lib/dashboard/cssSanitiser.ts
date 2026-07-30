import * as csstree from "css-tree";

/**
 * Scoped + fail-closed custom-CSS sanitiser for the App Builder.
 *
 * App authors may supply custom CSS to brand their app. Before that CSS is
 * injected into the page it is:
 *
 *  - SCOPED: every selector is rewritten to descend from the app root
 *    (`[data-saiku-app="<id>"]`) so it cannot style Saiku chrome outside the
 *    app or leak across embedded apps.
 *  - SANITISED: hostile constructs are stripped — `@import`, `@font-face`,
 *    `@charset`, `@namespace`, remote `url()` (only `data:` and
 *    relative/same-origin are allowed), `position: fixed`, CSS
 *    `expression(...)`, `behavior`, `-moz-binding`.
 *
 * It FAILS CLOSED: if the CSS cannot be fully parsed (or any transform step
 * throws) the whole stylesheet is dropped and `""` is returned. It never
 * throws.
 */

/** Declaration properties that are removed outright regardless of value. */
const FORBIDDEN_DECL = /^(behavior|-moz-binding)$/i;

/** At-rules removed outright (name compared case-insensitively). */
const FORBIDDEN_ATRULES = ["import", "font-face", "charset", "namespace"];

/**
 * True when a declaration's value is hostile for the given property and the
 * whole declaration must be dropped.
 */
function valueIsHostile(prop: string, value: string): boolean {
  const v = value.toLowerCase();
  if (/expression\s*\(/.test(v)) return true;
  if (prop.toLowerCase() === "position" && /\bfixed\b/.test(v)) return true;
  return false;
}

/**
 * True when a `url(...)` reference is allowed. Only `data:` URIs and
 * relative/same-origin references pass; absolute schemes (`https://`,
 * `http://`, `javascript:`, etc.) and protocol-relative (`//host`) references
 * are rejected.
 */
function urlIsAllowed(raw: string): boolean {
  const u = raw.trim().replace(/^['"]|['"]$/g, "");
  if (u.startsWith("data:")) return true;
  if (u.startsWith("//")) return false;
  if (/^[a-z][a-z0-9+.-]*:/i.test(u)) return false;
  return true;
}

export function sanitiseAndScopeCss(css: string | undefined, rootSelector: string): string {
  if (!css || !css.trim()) return "";

  let ast: csstree.CssNode;
  try {
    ast = csstree.parse(css, {
      onParseError: (e) => {
        throw e;
      },
    });
  } catch {
    return "";
  }

  try {
    // Drop hostile at-rules (@import, @font-face, @charset, @namespace).
    csstree.walk(ast, {
      visit: "Atrule",
      enter(node, item, list) {
        if (FORBIDDEN_ATRULES.includes(node.name.toLowerCase())) {
          list.remove(item);
        }
      },
    });

    // Drop hostile declarations (behavior, -moz-binding, position:fixed, expression()).
    csstree.walk(ast, {
      visit: "Declaration",
      enter(node, item, list) {
        if (FORBIDDEN_DECL.test(node.property)) {
          list.remove(item);
          return;
        }
        if (valueIsHostile(node.property, csstree.generate(node.value))) {
          list.remove(item);
        }
      },
    });

    // Neutralise remote url() references (keep data: and relative/same-origin).
    csstree.walk(ast, {
      visit: "Url",
      enter(node) {
        const urlNode = node as csstree.Url;
        const raw = typeof urlNode.value === "string" ? urlNode.value : csstree.generate(node);
        if (!urlIsAllowed(raw)) {
          urlNode.value = "";
        }
      },
    });

    // Scope every selector under the app root.
    csstree.walk(ast, {
      visit: "Selector",
      enter(node) {
        node.children.prependData({ type: "Combinator", name: " " } as csstree.CssNode);
        node.children.prependData({ type: "Raw", value: rootSelector } as csstree.CssNode);
      },
    });

    return csstree.generate(ast);
  } catch {
    return "";
  }
}
