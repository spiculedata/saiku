<!--
  `npm run check` will warn `options_missing_custom_element` on this
  file. Same false positive as SaikuEmbed.svelte — svelte-check sweeps
  against the SvelteKit tsconfig, which doesn't set the customElement
  compile flag; only vite.config.embed.ts does. Warning is exit-0.
-->
<svelte:options
	customElement={{
		tag: 'saiku-chart',
		shadow: 'open',
		props: {
			server: { type: 'String', attribute: 'server', reflect: false },
			token: { type: 'String', attribute: 'token', reflect: false },
			path: { type: 'String', attribute: 'path', reflect: false },
			mode: { type: 'String', attribute: 'mode', reflect: false },
			height: { type: 'String', attribute: 'height', reflect: false },
			filter: { type: 'String', attribute: 'filter', reflect: false },
			theme: { type: 'String', attribute: 'theme', reflect: true }
		}
	}}
/>

<script lang="ts">
	/*
	 * <saiku-chart> Web Component — issue #1103. A purpose-built split of
	 * <saiku-embed kind="query" render="chart">: same query→chart path,
	 * same wire contract, but its own tag so a host page that only wants a
	 * chart doesn't have to reach for the general-purpose element with its
	 * kind/render attribute pair. <saiku-embed> keeps working unchanged —
	 * this is an additive, narrower entry point, not a replacement.
	 *
	 * Attributes:
	 *   server  — origin of the Saiku launcher. Empty = same-origin.
	 *   token   — opaque embed token from POST /saiku/api/embed/tokens.
	 *             Omit for anonymous public reads.
	 *   path    — repository path of the saved query, ending .saiku.
	 *   mode    — chart type: "bar" (default), "line", or "pie".
	 *   height  — CSS height for the rendered surface (default 400px).
	 *   filter  — JSON array of slicer overrides applied at embed time.
	 *   theme   — "dark", "light", or "auto" (follow prefers-color-scheme).
	 *
	 * Outbound events (CustomEvent, bubbles + composed):
	 *   saiku:load   — detail {kind: "chart", rows} after the query loads
	 *   saiku:error  — detail {message} when the query load fails
	 *
	 * Chart series colours theme via the same --saiku-embed-chart-1..8 /
	 * --saiku-embed-fg / --saiku-embed-muted CSS custom properties
	 * <saiku-embed> uses (embedChartTheme.ts) — one shared vocabulary
	 * across every embed tag, since custom properties cascade regardless
	 * of which tag they're set on.
	 */
	import EmbedChart from './EmbedChart.svelte';
	import { fetchSavedQuery, EmbedFetchError, type EmbedFilterOverride } from './api';
	import type { EmbedRow } from './types';

	interface Props {
		server?: string;
		token?: string;
		path?: string;
		mode?: string;
		height?: string;
		filter?: string;
		theme?: string;
	}

	let {
		server = '',
		token = '',
		path = '',
		mode = 'bar',
		height = '400px',
		filter = ''
	}: Props = $props();

	let rows = $state<EmbedRow[] | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(false);
	let rootEl = $state<HTMLDivElement | undefined>(undefined);

	function emit(type: string, detail: unknown): void {
		rootEl?.dispatchEvent(new CustomEvent(type, { detail, bubbles: true, composed: true }));
	}

	/** Same tolerant parse as <saiku-embed>: a malformed filter attribute
	 *  degrades to an unfiltered query rather than throwing. */
	function parseFilter(raw: string): EmbedFilterOverride[] {
		const s = raw.trim();
		if (!s) return [];
		try {
			const v = JSON.parse(s);
			return Array.isArray(v) ? (v as EmbedFilterOverride[]) : [];
		} catch {
			return [];
		}
	}

	$effect(() => {
		const s = server.trim();
		const p = path.trim();
		const t = token.trim();
		const f = parseFilter(filter);
		if (!p) {
			rows = null;
			error = null;
			return;
		}
		let cancelled = false;
		loading = true;
		error = null;
		fetchSavedQuery(s, p, t || undefined, 'records', f)
			.then((resp) => {
				if (cancelled) return;
				rows = resp.data ?? [];
				emit('saiku:load', { kind: 'chart', rows: rows.length });
			})
			.catch((e: unknown) => {
				if (cancelled) return;
				const msg = friendlyError(e);
				error = msg;
				rows = null;
				emit('saiku:error', { message: msg });
			})
			.finally(() => {
				if (!cancelled) loading = false;
			});
		return () => {
			cancelled = true;
		};
	});

	/** Same opaque-error posture as <saiku-embed>: the host page is a third
	 *  party that doesn't need to know whether the failure was an expired
	 *  token, a wrong path, or a revoke. */
	function friendlyError(e: unknown): string {
		if (e instanceof EmbedFetchError) {
			if (e.status === 401) return 'This embed is unavailable.';
			return e.body.error ?? `Embed failed (${e.status}).`;
		}
		return 'Embed failed to load.';
	}
</script>

<div class="h-full w-full overflow-auto" style="min-height: {height};" bind:this={rootEl}>
	{#if loading && rows === null}
		<div class="state">Loading…</div>
	{:else if error}
		<div class="state error" role="alert">{error}</div>
	{:else if rows !== null}
		<EmbedChart {rows} {mode} />
	{:else}
		<div class="state muted">
			Configure the embed: <code>server</code> + <code>path</code>.
		</div>
	{/if}
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
		--saiku-embed-error: #f87171;
	}
	@media (prefers-color-scheme: dark) {
		:host([theme='auto']) {
			--saiku-embed-fg: #e6e8f0;
			--saiku-embed-bg: #0f1420;
			--saiku-embed-muted: #9aa2b4;
			--saiku-embed-error: #f87171;
		}
	}

	.state {
		padding: 16px;
		font-family: system-ui, sans-serif;
		font-size: 13px;
	}
	.state.muted {
		color: var(--saiku-embed-muted, #6b7280);
	}
	.state.error {
		color: var(--saiku-embed-error, #b91c1c);
	}
	code {
		background: rgba(0, 0, 0, 0.05);
		padding: 1px 4px;
		border-radius: 3px;
		font-family: ui-monospace, monospace;
	}
</style>
