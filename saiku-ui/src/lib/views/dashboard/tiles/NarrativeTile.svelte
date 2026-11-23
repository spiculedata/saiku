<script lang="ts">
	/*
	 * Dashboard narrative summary tile (saiku#910, Tier-2 aggregated).
	 *
	 * Self-contained: given the dashboard's title and the list of currently
	 * VISIBLE tiles' already filter-resolved queries (the same AiQueryRequest
	 * shape each ChartTile/TableTile/KpiTile sends to /ai/query itself), this
	 * component calls POST /ai/narrate-dashboard and renders the returned
	 * 2-4 sentence narrative. Regenerates (debounced) whenever `tiles`
	 * changes — i.e. whenever the caller re-resolves tile queries after a
	 * filter change.
	 *
	 * Wiring note: this component does NOT itself discover which tiles are
	 * visible or resolve their filters — the caller (the dashboard page /
	 * grid) owns that and passes the resolved list in. That keeps this tile
	 * decoupled from the cascading-filter / visibility logic living in
	 * dashboardStore + activeFilters.
	 *
	 * Markdown rendering mirrors TextTile.svelte: renderTinyMarkdown escapes
	 * source nodes before emitting its small safe tag set, DOMPurify is
	 * defence-in-depth on top since the text is LLM-generated (untrusted).
	 */

	import DOMPurify from 'dompurify';
	import { Sparkles, RotateCw } from '@lucide/svelte';
	import { renderTinyMarkdown } from '$lib/api/tinyMarkdown';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { narrateDashboard, type NarrativeTileInput } from '$lib/api/aiNarrateDashboard';
	import { AiAskTransportError } from '$lib/api/aiAsk';

	interface Props {
		dashboardTitle?: string;
		tiles: NarrativeTileInput[];
	}

	let { dashboardTitle, tiles }: Props = $props();

	/** Debounce window (ms) before a tiles-prop change triggers a re-generate — matches the design's
	 *  "regenerates when filters change (debounced 2s)". */
	const DEBOUNCE_MS = 2000;

	const SANITISE_CONFIG = { FORBID_TAGS: ['style', 'embed', 'object', 'iframe', 'form'] };

	let narrative = $state<string | null>(null);
	let loading = $state(false);
	let error = $state<string | null>(null);
	let abortController: AbortController | null = null;
	let debounceHandle: ReturnType<typeof setTimeout> | null = null;

	let safeHtml = $derived(
		narrative ? DOMPurify.sanitize(renderTinyMarkdown(narrative), SANITISE_CONFIG) : ''
	);

	async function generate() {
		abortController?.abort();
		const controller = new AbortController();
		abortController = controller;
		loading = true;
		error = null;
		try {
			const res = await narrateDashboard({ dashboardTitle, tiles }, controller.signal);
			if (controller.signal.aborted) return;
			if (res.degraded) {
				error =
					res.reason ?? i18n.t('dashboard.narrative.degraded', 'Could not generate a narrative.');
				narrative = null;
			} else {
				narrative = res.narrative ?? '';
			}
		} catch (e) {
			if ((e as Error)?.name === 'AbortError') return;
			error =
				e instanceof AiAskTransportError
					? e.message
					: i18n.t('dashboard.narrative.error', 'Something went wrong generating the narrative.');
			narrative = null;
		} finally {
			if (!controller.signal.aborted) loading = false;
		}
	}

	function scheduleGenerate() {
		if (debounceHandle) clearTimeout(debounceHandle);
		debounceHandle = setTimeout(generate, DEBOUNCE_MS);
	}

	$effect(() => {
		// Re-run whenever the caller hands us a new resolved tile list (e.g. after a filter change).
		void tiles;
		scheduleGenerate();
		return () => {
			if (debounceHandle) clearTimeout(debounceHandle);
		};
	});
</script>

<div class="narrative-tile">
	<div class="narrative-header">
		<Sparkles size={14} aria-hidden="true" />
		<span class="narrative-label">{i18n.t('dashboard.narrative.label', 'AI summary')}</span>
		<button
			type="button"
			class="narrative-refresh"
			disabled={loading}
			onclick={generate}
			title={i18n.t('dashboard.narrative.refresh', 'Regenerate')}
			aria-label={i18n.t('dashboard.narrative.refresh', 'Regenerate')}
		>
			<RotateCw size={13} aria-hidden="true" class={loading ? 'spinning' : ''} />
		</button>
	</div>
	{#if loading && !narrative}
		<p class="narrative-status">{i18n.t('dashboard.narrative.loading', 'Summarising…')}</p>
	{:else if error}
		<p class="narrative-status narrative-status--error">{error}</p>
	{:else if narrative}
		<!-- eslint-disable-next-line svelte/no-at-html-tags — sanitised via DOMPurify -->
		<div class="narrative-body">{@html safeHtml}</div>
	{/if}
</div>

<style>
	.narrative-tile {
		display: flex;
		flex-direction: column;
		gap: 0.375rem;
		padding: 0.5rem 0.75rem;
		height: 100%;
		box-sizing: border-box;
		color: var(--saiku-app-fg, hsl(var(--fg)));
	}
	.narrative-header {
		display: flex;
		align-items: center;
		gap: 0.375rem;
		color: hsl(var(--primary));
	}
	.narrative-label {
		font-size: 0.75rem;
		font-weight: var(--weight-semibold);
		text-transform: uppercase;
		letter-spacing: 0.02em;
	}
	.narrative-refresh {
		margin-left: auto;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		background: transparent;
		border: none;
		padding: 0.125rem;
		cursor: pointer;
		color: inherit;
		border-radius: 4px;
	}
	.narrative-refresh:hover:not(:disabled) {
		background: hsl(var(--bg-subtle));
	}
	.narrative-refresh:disabled {
		cursor: default;
		opacity: 0.6;
	}
	.narrative-refresh :global(.spinning) {
		animation: narrative-spin 1s linear infinite;
	}
	@keyframes narrative-spin {
		to {
			transform: rotate(360deg);
		}
	}
	.narrative-status {
		margin: 0;
		font-size: 0.8125rem;
		opacity: 0.75;
	}
	.narrative-status--error {
		color: hsl(var(--danger));
		opacity: 1;
	}
	.narrative-body {
		font-size: 0.875rem;
		line-height: 1.5;
	}
	.narrative-body :global(p) {
		margin: 0.25em 0;
	}
</style>
