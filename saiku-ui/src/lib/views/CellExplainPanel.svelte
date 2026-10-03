<!--
	"Explain this number" side panel (saiku#1118).

	Opened from the cellset's right-click menu ("Explain this number"). It fetches
	POST /saiku/api/ai/explain for the clicked cell and shows, in order: the number,
	its story, the structured findings behind that story, and the MDX / SQL that
	produced it. The MDX and SQL blocks are what makes the panel safe to trust —
	"show me" beats "tell me" — so they are one click away rather than hidden.

	Every section degrades independently: no SQL on this backend, or no LLM configured,
	removes its section and leaves the rest, with the server's own note explaining what
	didn't come back. The panel never substitutes a guess for a missing number.
-->
<script lang="ts">
	import { explainCell, type ExplainResponse } from '$lib/api/explain';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { X } from '@lucide/svelte';
	import { DRIVER_LABEL_KEYS, explainTitle, formatDriverValue } from '$lib/views/explainView';

	interface Props {
		open: boolean;
		/** Session query the cell belongs to. */
		queryName: string;
		/** Data row of the clicked cell (header band excluded). */
		row: number;
		/** Data column of the clicked cell (row-header band excluded). */
		column: number;
		onClose: () => void;
	}

	let { open, queryName, row, column, onClose }: Props = $props();

	let loading = $state(false);
	let result = $state<ExplainResponse | null>(null);
	let errorMessage = $state<string | null>(null);
	let showMdx = $state(false);
	let showSql = $state(false);

	// A new cell replaces the previous answer: clear first so a slow response can never
	// paint the previous cell's number next to the new cell's label.
	$effect(() => {
		if (!open) return;
		const name = queryName;
		const targetRow = row;
		const targetColumn = column;
		loading = true;
		errorMessage = null;
		result = null;
		showMdx = false;
		showSql = false;
		let cancelled = false;
		void (async () => {
			try {
				const explained = await explainCell({
					queryName: name,
					position: { row: targetRow, column: targetColumn }
				});
				if (!cancelled) result = explained;
			} catch (e) {
				if (cancelled) return;
				// Surfaced inline rather than as a toast: the panel is the thing the user is
				// looking at, and a toast would be a second place to look for the same sentence.
				errorMessage = e instanceof Error ? e.message : String(e);
			} finally {
				if (!cancelled) loading = false;
			}
		})();
		return () => {
			cancelled = true;
		};
	});

	function copy(text: string) {
		void navigator.clipboard?.writeText(text);
		toasts.success(i18n.t('cellset.explain.copied'));
	}
</script>

{#if open}
	<aside class="explain" aria-label={i18n.t('cellset.explain.title')}>
		<header class="explain__head">
			<div class="explain__headings">
				<h2 class="explain__title">{i18n.t('cellset.explain.title')}</h2>
				{#if result}
					<p class="explain__subject">{explainTitle(result)}</p>
				{/if}
			</div>
			<button
				type="button"
				class="explain__close"
				onclick={onClose}
				aria-label={i18n.t('modal.close')}
			>
				<X size={16} />
			</button>
		</header>

		<div class="explain__body">
			{#if loading}
				<p class="explain__status">{i18n.t('cellset.explain.loading')}</p>
			{:else if errorMessage}
				<p class="explain__status explain__status--danger">{errorMessage}</p>
			{:else if result}
				<p class="explain__value">{result.formatted ?? result.value}</p>

				{#if result.narrative}
					<section class="explain__section">
						<h3 class="explain__section-title">
							{i18n.t('cellset.explain.narrative')}
							{#if result.narrativeSource === 'LLM' && result.model}
								<span class="explain__badge">{result.model}</span>
							{/if}
						</h3>
						<p class="explain__prose">{result.narrative}</p>
					</section>
				{/if}

				{#if result.drivers.length > 0}
					<section class="explain__section">
						<h3 class="explain__section-title">{i18n.t('cellset.explain.drivers')}</h3>
						<dl class="explain__drivers">
							{#each result.drivers as driver (driver.kind + driver.caption)}
								<div class="explain__driver">
									<dt>{i18n.t(DRIVER_LABEL_KEYS[driver.kind])}</dt>
									<dd>
										<span class="explain__driver-caption">{driver.caption}</span>
										<span class="explain__driver-value">{formatDriverValue(driver)}</span>
									</dd>
								</div>
							{/each}
						</dl>
					</section>
				{/if}

				{#if result.cellMdx}
					<section class="explain__section">
						<button type="button" class="explain__toggle" onclick={() => (showMdx = !showMdx)}>
							{i18n.t('cellset.explain.mdx')}
							<span class="explain__chevron">{showMdx ? '▾' : '▸'}</span>
						</button>
						{#if showMdx}
							<pre class="explain__code">{result.cellMdx}</pre>
							<button type="button" class="explain__copy" onclick={() => copy(result!.cellMdx!)}>
								{i18n.t('cellset.explain.copy')}
							</button>
						{/if}
					</section>
				{/if}

				{#if result.sql}
					<section class="explain__section">
						<button type="button" class="explain__toggle" onclick={() => (showSql = !showSql)}>
							{i18n.t('cellset.explain.sql')}
							<span class="explain__chevron">{showSql ? '▾' : '▸'}</span>
						</button>
						{#if showSql}
							<pre class="explain__code">{result.sql}</pre>
							<button type="button" class="explain__copy" onclick={() => copy(result!.sql!)}>
								{i18n.t('cellset.explain.copy')}
							</button>
						{/if}
					</section>
				{/if}

				{#if result.notes && result.notes.length > 0}
					<section class="explain__section">
						<ul class="explain__notes">
							{#each result.notes as note (note)}
								<li>{note}</li>
							{/each}
						</ul>
					</section>
				{/if}

				{#if result.elapsedMs !== undefined}
					<p class="explain__timing">{result.elapsedMs} {i18n.t('units.ms')}</p>
				{/if}
			{/if}
		</div>
	</aside>
{/if}

<style>
	.explain {
		display: flex;
		flex-direction: column;
		width: 380px;
		flex: 0 0 380px;
		min-height: 0;
		border-left: 1px solid hsl(var(--border));
		background: hsl(var(--bg));
	}
	.explain__head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 8px;
		padding: 10px 12px;
		border-bottom: 1px solid hsl(var(--border));
	}
	.explain__headings {
		min-width: 0;
	}
	.explain__title {
		font-size: var(--fs-sm);
		font-weight: var(--weight-semibold);
	}
	.explain__subject {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-muted));
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.explain__close {
		color: hsl(var(--fg-muted));
	}
	.explain__body {
		flex: 1;
		min-height: 0;
		overflow: auto;
		padding: 12px;
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.explain__status {
		font-size: var(--fs-sm);
		color: hsl(var(--fg-muted));
	}
	.explain__status--danger {
		color: hsl(var(--danger));
	}
	.explain__value {
		font-size: 1.5rem;
		font-weight: var(--weight-semibold);
	}
	.explain__section {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.explain__section-title {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: var(--fs-xs);
		font-weight: var(--weight-semibold);
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: hsl(var(--fg-subtle));
	}
	.explain__badge {
		font-weight: var(--weight-regular);
		text-transform: none;
		letter-spacing: 0;
		color: hsl(var(--fg-muted));
	}
	.explain__prose {
		font-size: var(--fs-sm);
		line-height: 1.5;
		white-space: pre-wrap;
	}
	.explain__drivers {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.explain__driver {
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding: 6px 8px;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
	}
	.explain__driver dt {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-subtle));
	}
	.explain__driver dd {
		display: flex;
		justify-content: space-between;
		gap: 8px;
		font-size: var(--fs-sm);
	}
	.explain__driver-value {
		font-variant-numeric: tabular-nums;
	}
	.explain__toggle {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: var(--fs-xs);
		font-weight: var(--weight-semibold);
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: hsl(var(--fg-subtle));
	}
	.explain__chevron {
		color: hsl(var(--fg-muted));
	}
	.explain__code {
		margin: 0;
		padding: 8px;
		max-height: 220px;
		overflow: auto;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		background: hsl(var(--bg-muted));
		font-family: var(--font-mono);
		font-size: var(--fs-xs);
		white-space: pre-wrap;
		word-break: break-word;
	}
	.explain__copy {
		align-self: flex-start;
		font-size: var(--fs-xs);
		color: hsl(var(--fg-muted));
	}
	.explain__notes {
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 4px;
		font-size: var(--fs-xs);
		color: hsl(var(--fg-muted));
	}
	.explain__timing {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-subtle));
	}
</style>
