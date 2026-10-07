<!--
  `npm run check` will warn `options_missing_custom_element` on this
  file. Same false positive as SaikuEmbed.svelte — see its comment.
-->
<svelte:options
	customElement={{
		tag: 'saiku-dashboard',
		shadow: 'open',
		props: {
			server: { type: 'String', attribute: 'server', reflect: false },
			token: { type: 'String', attribute: 'token', reflect: false },
			path: { type: 'String', attribute: 'path', reflect: false },
			height: { type: 'String', attribute: 'height', reflect: false },
			theme: { type: 'String', attribute: 'theme', reflect: true }
		}
	}}
/>

<script lang="ts">
	/*
	 * <saiku-dashboard> Web Component — issue #1103. A purpose-built split
	 * of <saiku-embed kind="dashboard">: same fetch + tile-grid rendering
	 * path (EmbedDashboard / EmbedGrid, shared with the App Builder embed),
	 * but its own tag so a host page embedding a dashboard doesn't have to
	 * reach for the general-purpose element with a kind attribute.
	 * <saiku-embed kind="dashboard"> keeps working unchanged.
	 *
	 * Attributes:
	 *   server  — origin of the Saiku launcher. Empty = same-origin.
	 *   token   — opaque embed token from POST /saiku/api/embed/tokens.
	 *             Omit for anonymous public reads.
	 *   path    — repository path of the saved dashboard, ending .saikudash.
	 *   height  — CSS height for the rendered surface (default 400px).
	 *   theme   — "dark", "light", or "auto" (follow prefers-color-scheme).
	 *
	 * No outbound CustomEvents yet — <saiku-embed kind="dashboard"> doesn't
	 * emit any either; per-tile interaction (filter tiles, drill) is
	 * internal to EmbedGrid today.
	 */
	import EmbedDashboard from './EmbedDashboard.svelte';

	interface Props {
		server?: string;
		token?: string;
		path?: string;
		height?: string;
		theme?: string;
	}

	let { server = '', token = '', path = '', height = '400px' }: Props = $props();
</script>

<div class="h-full w-full overflow-auto" style="min-height: {height};">
	<EmbedDashboard {server} {token} {path} />
</div>

<style>
	/* Shadow-DOM scoped, same theme-var vocabulary as <saiku-embed> — see
	   its SaikuEmbed.svelte for the rationale. Deliberately duplicated
	   rather than shared: each custom element compiles to its own shadow
	   root, so there's no import path for shared <style> across tags. */
	:host {
		display: block;
		color: var(--saiku-embed-fg, #1f2937);
		background: var(--saiku-embed-bg, transparent);
	}

	:host([theme='dark']) {
		--saiku-embed-fg: #e6e8f0;
		--saiku-embed-bg: #0f1420;
		--saiku-embed-muted: #9aa2b4;
		--saiku-embed-border: #2a3140;
		--saiku-embed-header-bg: #171d2a;
		--saiku-embed-row-hover: #1b2230;
		--saiku-embed-error: #f87171;
		--saiku-embed-negative: #f87171;
		--saiku-embed-positive: #34d399;
		--saiku-embed-accent: #6d7dff;
	}
	@media (prefers-color-scheme: dark) {
		:host([theme='auto']) {
			--saiku-embed-fg: #e6e8f0;
			--saiku-embed-bg: #0f1420;
			--saiku-embed-muted: #9aa2b4;
			--saiku-embed-border: #2a3140;
			--saiku-embed-header-bg: #171d2a;
			--saiku-embed-row-hover: #1b2230;
			--saiku-embed-error: #f87171;
			--saiku-embed-negative: #f87171;
			--saiku-embed-positive: #34d399;
			--saiku-embed-accent: #6d7dff;
		}
	}
</style>
