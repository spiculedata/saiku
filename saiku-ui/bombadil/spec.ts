/**
 * Bombadil property-based fuzz spec for the Saiku UI.
 *
 * Bombadil (https://github.com/antithesishq/bombadil) autonomously explores the
 * app in a headless browser and checks the properties exported here on every
 * captured state — surfacing crashes, console errors, failed requests, and
 * broken states a scripted e2e wouldn't reach. Run it with `npm run fuzz`
 * (see ./README.md); that harness injects an authenticated `JSESSIONID` cookie
 * so the fuzzer starts logged in.
 *
 * Ported from saiku-cloud's `dashboard/bombadil/dashboard.spec.ts` and adapted
 * for saiku: different auth (Spring Security session cookie, not WorkOS) and no
 * top-level `+error.svelte`, so 5xx crashes are caught by the built-in
 * `noHttpErrorCodes` property rather than an error-page invariant.
 *
 * ## Properties (correctness invariants)
 * Bombadil's built-in browser defaults — the high-value, low-noise baseline: no
 * uncaught exceptions, no unhandled promise rejections, no console errors, no
 * failed HTTP requests. `noHttpErrorCodes` fires on any >= 400 response, so a
 * long run WILL flag 4xx from fuzzed URLs (a 404 for a made-up path, a 403 on a
 * CSRF-guarded POST) alongside the real 5xx server bugs — triage those out in
 * the report; the 5xx are the ones that matter.
 *
 * ## Actions (why we DON'T use `defaultActions`)
 * The default action set clicks *everything*, including **Sign out** (which ends
 * the injected session → every later request 401s and bounces to /login, wasting
 * the run) and, on /login, the **Sign in** submit. So we compose our own set:
 * every default action EXCEPT the raw `clicks` (replaced by an auth-avoiding
 * `safeClicks`) and `navigation` (which could jump straight to /login|/logout).
 * This keeps the fuzzer inside the authenticated app for the full run.
 */
import { extract, actions, type Action } from '@antithesishq/bombadil/browser';

// Correctness properties — the built-in defaults (kept as-is).
export {
	noUncaughtExceptions,
	noUnhandledPromiseRejections,
	noConsoleErrors,
	noHttpErrorCodes
} from '@antithesishq/bombadil/browser/defaults/properties';

// Safe default actions — everything except `clicks` (replaced below) and
// `navigation` (could navigate directly to /login or /logout).
export {
	waitOnce,
	scroll,
	inputs,
	back,
	forward,
	reload
} from '@antithesishq/bombadil/browser/defaults/actions';

/**
 * Auth controls that must never be clicked — following any of them logs the
 * fuzzer out (Sign out) or re-submits login, both of which strand the run on
 * /login. Testids added in +layout.svelte / LoginForm.svelte for a stable hook.
 */
const AUTH_EXCLUDE = [
	'[data-testid="app-signout"]',
	'[data-testid="login-submit"]',
	'a[href*="/login"]',
	'a[href*="/logout"]',
	// Non-http links hang Bombadil's navigation (30s timeout -> fatal). The
	// protocol guard below is the real catch-all; these are belt-and-suspenders.
	'a[href^="mailto:"]',
	'a[href^="tel:"]'
].join(', ');

/** True for links that navigate somewhere Bombadil can't (mailto:, tel:, blob:, …). */
function isNonHttpLink(el: Element): boolean {
	if (el.tagName !== 'A') return false;
	const proto = (el as HTMLAnchorElement).protocol;
	return proto !== '' && proto !== 'http:' && proto !== 'https:';
}

const CLICKABLE =
	'a, button, [role="button"], input[type="submit"], input[type="button"], summary, [onclick]';

/**
 * Clickable elements currently on screen, MINUS the auth controls. Runs in-page,
 * so `getBoundingClientRect` gives real viewport coordinates; we keep only
 * visible, in-viewport targets (mirrors what the default clicks consider).
 */
const clickTargets = extract((state): Array<{ name: string; x: number; y: number }> => {
	const doc = state.document;
	const win = state.window;
	const excluded = new Set<Element>(Array.from(doc.querySelectorAll(AUTH_EXCLUDE)));
	return Array.from(doc.querySelectorAll(CLICKABLE))
		.filter((el) => !excluded.has(el) && el.closest(AUTH_EXCLUDE) === null && !isNonHttpLink(el))
		.map((el) => {
			const r = el.getBoundingClientRect();
			return {
				name: el.getAttribute('data-testid') || el.tagName,
				x: r.left + r.width / 2,
				y: r.top + r.height / 2,
				w: r.width,
				h: r.height
			};
		})
		.filter(
			(t) =>
				t.w > 0 &&
				t.h > 0 &&
				t.x >= 0 &&
				t.y >= 0 &&
				t.x <= win.innerWidth &&
				t.y <= win.innerHeight
		)
		.map(({ name, x, y }) => ({ name, x, y }));
});

/** Click any on-screen element that isn't an auth control. */
export const safeClicks = actions((): Action[] =>
	clickTargets.current.map((t) => ({ Click: { name: t.name, point: { x: t.x, y: t.y } } }))
);
