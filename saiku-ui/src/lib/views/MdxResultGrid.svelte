<script lang="ts">
	/*
	 * Read-only cellset grid for the MDX workbench (saiku#1106 phase 1).
	 *
	 * Deliberately NOT a reuse of CellsetTable: that component wires its
	 * right-click menu and drag-select straight into the singleton
	 * `query`/`selection` stores that back the main workspace canvas, so
	 * reusing it here would let a click in the workbench silently mutate and
	 * re-run whatever query tab the user has open in the main workspace. The
	 * issue's own test plan calls this a "read-only result view" — this
	 * component honours that by only ever reading `result`, built on the same
	 * pure cellset-parsing helpers CellsetTable uses.
	 */
	import type { QueryResult } from '$lib/api/query';
	import { parseCellset, rowHeaderDisplay } from '$lib/views/cellsetUtils';
	import { parseFormattedCell } from '$lib/cellset/cellFormat';
	import { i18n } from '$lib/stores/i18n.svelte';

	interface Props {
		result: QueryResult | null;
	}

	let { result }: Props = $props();

	let parsed = $derived(result ? parseCellset(result) : null);
	let rowDisplay = $derived(parsed ? rowHeaderDisplay(parsed) : []);

	function depthOf(uniqueName: string | undefined): number {
		if (!uniqueName) return 0;
		const m = uniqueName.match(/\]\.\[/g);
		return Math.max(0, (m?.length ?? 0) - 1);
	}
</script>

{#if result?.error}
	<p class="callout callout--danger" role="alert">{result.error}</p>
{:else if !result || (result.cellset?.length ?? 0) === 0}
	<p class="p-4 text-fg-muted">{i18n.t('workbench.result.empty', 'Run a query to see results.')}</p>
{:else if parsed}
	<div class="mdx-result-wrap" role="table" aria-label={i18n.t('a11y.cellsetGrid')}>
		<table class="mdx-result">
			<thead>
				{#each parsed.columnHeaderRows as hdrRow, rIdx (rIdx)}
					<tr>
						{#each hdrRow as c, cIdx (cIdx)}
							{#if cIdx < parsed.rowHeaderColCount}
								<th class="mdx-result__corner"></th>
							{:else if c.type === 'COLUMN_HEADER'}
								<th class="mdx-result__col" title={c.value}>{c.value || ' '}</th>
							{:else}
								<th class="mdx-result__col-null"></th>
							{/if}
						{/each}
					</tr>
				{/each}
			</thead>
			<tbody>
				{#each parsed.bodyRows as rowCells, r (r)}
					<tr>
						{#each rowCells as c, cIdx (cIdx)}
							{@const display = rowDisplay[r]?.[cIdx]}
							{#if display?.isNull}
								<th class="mdx-result__row-null"></th>
							{:else}
								{@const d = depthOf(c.properties?.uniquename)}
								<th
									class="mdx-result__row"
									style={d > 0 ? `padding-left: calc(var(--space-3) + ${d}em);` : ''}
									title={c.value}>{c.value}</th
								>
							{/if}
						{/each}
						{#each parsed.dataRows[r] as dc, cIdx (cIdx)}
							{@const fmt = parseFormattedCell(dc.value)}
							<td class="mdx-result__data" style={fmt.color ? `color: ${fmt.color}` : undefined}
								>{fmt.display}</td
							>
						{/each}
					</tr>
				{/each}
			</tbody>
		</table>
	</div>
	{#if result.runtime != null}
		<p class="mdx-result__runtime text-fg-muted">
			{i18n.t('stats.runtime', 'Runtime')}: {result.runtime}ms
		</p>
	{/if}
{/if}

<style>
	.mdx-result-wrap {
		overflow: auto;
		max-height: 100%;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
	}
	.mdx-result {
		border-collapse: collapse;
		font-size: var(--fs-sm);
		white-space: nowrap;
	}
	.mdx-result th,
	.mdx-result td {
		border: 1px solid hsl(var(--border));
		padding: 2px 8px;
	}
	.mdx-result thead th {
		position: sticky;
		top: 0;
		background: hsl(var(--bg-muted));
		font-weight: var(--weight-semibold);
		text-align: left;
		z-index: 1;
	}
	.mdx-result__corner,
	.mdx-result__col-null,
	.mdx-result__row-null {
		background: hsl(var(--bg-muted));
	}
	.mdx-result tbody th {
		background: hsl(var(--bg-subtle));
		font-weight: var(--weight-medium);
		text-align: left;
		position: sticky;
		left: 0;
	}
	.mdx-result__data {
		text-align: right;
		background: hsl(var(--bg));
	}
	.mdx-result__runtime {
		font-size: var(--fs-xs);
		margin: var(--space-1) 0 0;
	}
</style>
