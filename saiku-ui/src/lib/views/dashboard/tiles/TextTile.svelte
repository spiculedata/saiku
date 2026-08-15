<script lang="ts">
	/*
	 * Text annotation tile. Stores markdown in tile.text, renders it to
	 * HTML with renderTinyMarkdown, then sanitises the result via DOMPurify
	 * before insertion. No query, no filters, no subscriptions — purely
	 * declarative.
	 *
	 * Why two stages: renderTinyMarkdown escapes every source node before
	 * emitting the small set of structural tags it supports (headings,
	 * bold/italic, bullets, inline code, http(s) links) — so its output is
	 * already HTML-safe, exactly as it is trusted in the AI Ask drawer.
	 * DOMPurify is kept as defence-in-depth because a TextTile's text is
	 * analyst-controllable (JCR write access), so a bug in the renderer must
	 * not become a stored-XSS sink.
	 *
	 * Threat model: a malicious analyst with write access to the JCR
	 * repository injects script tags or javascript: URLs. renderTinyMarkdown
	 * escapes them to inert text; DOMPurify additionally strips scripts,
	 * event handlers, and dangerous URL schemes. A sanity test in the
	 * project's vitest harness asserts the common XSS payloads don't survive
	 * (task #14).
	 */

	import DOMPurify from 'dompurify';
	import { renderTinyMarkdown } from '$lib/api/tinyMarkdown';
	import type { DashboardTile } from '$lib/api/dashboards';

	interface Props {
		tile: DashboardTile;
	}

	let { tile }: Props = $props();

	// Stricter than DOMPurify's html-profile defaults — also forbid
	// style / embed / object / iframe / form tags so analysts can't
	// smuggle CSS-based exfil, inline plugins, or hidden form posts
	// through a TextTile. Script tags, event handlers, and
	// javascript:/data: URLs are stripped by DOMPurify's defaults.
	//
	// Note: USE_PROFILES has an additive effect that re-allows some of
	// these tags (notably embed when SVG appears in the input),
	// overriding FORBID_TAGS. Drop the profile and use DOMPurify's
	// default-strict mode — it covers the OWASP vectors we care about
	// and respects our forbid list cleanly.
	const SANITISE_CONFIG = {
		FORBID_TAGS: ['style', 'embed', 'object', 'iframe', 'form']
	};

	let safeHtml = $derived(DOMPurify.sanitize(renderTinyMarkdown(tile.text ?? ''), SANITISE_CONFIG));
</script>

<div class="text-tile">
	<!-- eslint-disable-next-line svelte/no-at-html-tags — sanitised via DOMPurify -->
	{@html safeHtml}
</div>

<style>
	.text-tile {
		padding: 0.25rem 0.5rem;
		font-size: 0.875rem;
		line-height: 1.5;
		/* saiku#1787: the app theme leads, the Saiku chrome is the fallback.
       Inside an App Builder shell the surrounding chrome is dark while the
       app's card is light, so hsl(var(--fg)) painted near-white text onto a
       white card (1.02:1 — invisible). --saiku-app-fg is emitted by appSkin.ts
       on the app root and is unset on a plain dashboard, where the chrome
       token is the correct ground. */
		color: var(--saiku-app-fg, hsl(var(--fg)));
	}
	/* Heading / paragraph / list resets so analyst-written text doesn't
     inherit weird vertical rhythm from the surrounding tile chrome.
     Covers h1–h6 because renderTinyMarkdown maps # … #### onto h3–h6 —
     h4/h5/h6 were previously missed, so a `### Heading` kept the global
     scale and, worse, the global colour.
     saiku#1787: `color: inherit` is load-bearing. app.css declares a global
     `h1…h6 { color: hsl(var(--fg)) }`, and an explicit colour on the heading
     beats the one inherited from .text-tile — so headings stayed painted in
     the Saiku chrome foreground (near-white) on the app's light card even
     after the body text was fixed. */
	.text-tile :global(h1),
	.text-tile :global(h2),
	.text-tile :global(h3),
	.text-tile :global(h4),
	.text-tile :global(h5),
	.text-tile :global(h6) {
		margin: 0.25em 0;
		font-weight: var(--weight-semibold);
		color: inherit;
	}
	.text-tile :global(p) {
		margin: 0.25em 0;
	}
	.text-tile :global(ul),
	.text-tile :global(ol) {
		margin: 0.25em 0;
		padding-left: 1.25rem;
	}
	.text-tile :global(code) {
		background: hsl(var(--bg-subtle));
		padding: 0.0625em 0.25em;
		border-radius: 3px;
		font-size: 0.85em;
	}
	.text-tile :global(a) {
		color: hsl(var(--primary));
		text-decoration: underline;
	}
</style>
