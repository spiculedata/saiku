/*
 * Host side of the sandboxed-plugin postMessage protocol (App Builder Phase 2,
 * Task 7, saiku#1441).
 *
 * SECURITY BOUNDARY. A `plugin` tile runs ARBITRARY author JavaScript inside a
 * locked-down iframe (sandbox="allow-scripts", strict CSP, opaque "null"
 * origin). The host and the plugin talk ONLY through window.postMessage, and
 * this module is the host's parser/validator for everything the plugin sends.
 *
 * Design rules that make it airtight:
 *   - This module is PURE (no DOM, no fetch, no imports). It only parses +
 *     validates the message shape and clamps numbers. Every host action (set
 *     iframe height, resolve a filter against the live cube, show an error)
 *     lives in the component and consumes ONLY the typed, validated result
 *     returned here — the raw plugin payload never reaches the host DOM.
 *   - Authentication is a per-mount cryptographic `nonce`. A sandboxed frame's
 *     origin is the string "null", so event.origin is useless as an
 *     authenticator; instead every plugin→host message MUST echo the nonce the
 *     host injected into that frame's srcdoc. Wrong/absent nonce → dropped.
 *   - Fail closed: any shape we don't recognise returns null (ignored).
 *
 * The component ALSO guards event.source === iframe.contentWindow before this
 * ever runs, so one tile's host can never process another frame's messages —
 * the nonce is the second, defence-in-depth authenticator.
 */

/** Clamp bounds for a plugin-requested iframe height (px). A plugin cannot make
 *  its tile collapse to nothing or grow unboundedly to cover the host chrome. */
export const PLUGIN_MIN_H = 40;
export const PLUGIN_MAX_H = 4000;

/** A validated, server-resolvable filter selection (captions/levels the host
 *  will re-resolve against the live cube — NOT trusted MDX). */
export interface FilterSel {
  dimension: string;
  hierarchy: string;
  level: string;
  members: string[];
}

/** Host → plugin envelope. `nonce` lets the plugin ignore stray messages too. */
export interface HostToPlugin {
  type: "init" | "data" | "theme" | "resize";
  nonce: string;
  payload: unknown;
}

/** Every message shape a plugin is permitted to send the host. */
export type PluginToHost =
  | { type: "ready"; nonce: string }
  | { type: "resize"; nonce: string; height: number }
  | {
      type: "filter";
      nonce: string;
      dimension: string;
      hierarchy: string;
      level: string;
      members: string[];
    }
  | { type: "error"; nonce: string; message: string };

/** Max members a single filter message may carry (anti-DoS). */
const MAX_FILTER_MEMBERS = 500;
/** Max length of a plugin error string surfaced in the tile chrome. */
const MAX_ERROR_LEN = 500;

/**
 * Parse + validate one raw plugin→host message against the expected nonce.
 *
 * Returns a narrow, typed result the component can act on, or `null` when the
 * message is unauthenticated or malformed (the component then ignores it).
 * NEVER throws — a plugin must not be able to crash the host by sending junk.
 */
export function handlePluginMessage(
  raw: unknown,
  expectNonce: string,
):
  | { kind: "ready" }
  | { kind: "resize"; height: number }
  | { kind: "filter"; sel: FilterSel }
  | { kind: "error"; message: string }
  | null {
  if (!raw || typeof raw !== "object") return null;
  const m = raw as Record<string, unknown>;
  // Authenticate: the sandboxed frame origin is "null", so the nonce — injected
  // per-mount into this frame's srcdoc — is the only trustworthy authenticator.
  if (m.nonce !== expectNonce) return null;
  switch (m.type) {
    case "ready":
      return { kind: "ready" };
    case "resize": {
      const h = Number(m.height);
      if (!Number.isFinite(h)) return null;
      return {
        kind: "resize",
        height: Math.min(PLUGIN_MAX_H, Math.max(PLUGIN_MIN_H, Math.round(h))),
      };
    }
    case "filter": {
      if (
        typeof m.dimension !== "string" ||
        typeof m.hierarchy !== "string" ||
        typeof m.level !== "string"
      ) {
        return null;
      }
      const members = Array.isArray(m.members)
        ? m.members.filter((x): x is string => typeof x === "string").slice(0, MAX_FILTER_MEMBERS)
        : [];
      return {
        kind: "filter",
        sel: { dimension: m.dimension, hierarchy: m.hierarchy, level: m.level, members },
      };
    }
    case "error":
      return { kind: "error", message: String(m.message ?? "").slice(0, MAX_ERROR_LEN) };
    default:
      return null;
  }
}

/**
 * The strict Content-Security-Policy every plugin frame is locked to. It is the
 * SECOND containment layer behind the iframe sandbox:
 *   - default-src 'none'     → deny everything not explicitly re-allowed below
 *                              (fonts, frames, workers, media, objects, …)
 *   - script-src 'unsafe-inline' → the plugin's own inline JS may run (that's
 *                              the whole point) but NO external script URLs
 *   - style-src 'unsafe-inline'  → inline styles only, no remote stylesheets
 *   - img-src data:          → only inline data: images (no remote image beacon
 *                              exfiltration; blocks <img src="https://evil…">)
 *   - connect-src 'none'     → NO network egress at all: fetch, XHR, WebSocket,
 *                              EventSource, sendBeacon are all blocked
 * Nothing here grants navigation, form submission, or plugins/objects.
 */
export const PLUGIN_CSP =
  "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'";

/** Max length of the author plugin HTML we persist (anti-bloat, not security —
 *  the sandbox+CSP are what contain the code). Generous for a self-contained
 *  single-file widget. */
export const PLUGIN_HTML_MAX_LEN = 200_000;

/** Validate + normalise a plugin tile's opaque options blob. Shape only:
 *  `{ html: string }` (plus any author config passed through). The HTML is NOT
 *  sanitised — arbitrary JS is the feature and the iframe sandbox + CSP are the
 *  containment. Returns the registry's ValidateOptionsResult shape. */
export function validatePluginOptions(
  options: unknown,
): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
  if (options === null || options === undefined) {
    return { ok: true, value: {} };
  }
  if (typeof options !== "object") {
    return { ok: false, error: "Plugin options must be an object." };
  }
  const o = options as Record<string, unknown>;
  if (o.html !== undefined && typeof o.html !== "string") {
    return { ok: false, error: "Plugin `html` must be a string." };
  }
  if (typeof o.html === "string" && o.html.length > PLUGIN_HTML_MAX_LEN) {
    return { ok: false, error: `Plugin HTML exceeds ${PLUGIN_HTML_MAX_LEN} characters.` };
  }
  return { ok: true, value: { ...o } };
}

/** Keep only characters that are safe inside a JS string / HTML — our own
 *  generated nonce is already in this set; this is belt-and-braces so a
 *  hostile nonce could never break out of the injected <script> context. */
function safeNonce(nonce: string): string {
  return String(nonce).replace(/[^A-Za-z0-9_-]/g, "");
}

/**
 * Wrap the author's self-contained plugin HTML into a full document for the
 * iframe `srcdoc`, with the strict CSP meta as the FIRST element in <head> and
 * the nonce exposed as a JS const the plugin reads to stamp its messages.
 *
 * PURE / string-only — no DOM. The author HTML is embedded verbatim in <body>;
 * containment is the sandbox attribute + the CSP above, NOT any sanitising of
 * the author code (arbitrary JS is the feature). The returned string is placed
 * into an HTML-attribute-escaped `srcdoc` by the component.
 */
export function buildSrcdoc(pluginHtml: string, nonce: string): string {
  const n = safeNonce(nonce);
  // CSP meta MUST be first so it governs everything that follows. The nonce
  // const is emitted via JSON.stringify over the sanitised value (defence in
  // depth) so it can never terminate the script early.
  return (
    "<!DOCTYPE html><html><head>" +
    `<meta http-equiv="Content-Security-Policy" content="${PLUGIN_CSP}">` +
    '<meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width, initial-scale=1">' +
    `<script>window.SAIKU_PLUGIN_NONCE=${JSON.stringify(n)};</script>` +
    "</head><body>" +
    (typeof pluginHtml === "string" ? pluginHtml : "") +
    "</body></html>"
  );
}
