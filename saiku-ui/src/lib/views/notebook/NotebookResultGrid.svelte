<script lang="ts">
	/*
	 * Read-only grid for a notebook MDX cell's result (issue #1108, phase 1:
	 * "results render in-cell as the grid", no chart cell yet). Deliberately
	 * NOT CellsetTable: that component is wired to the single global
	 * workspace query/selection stores for drill/zoom interactions, which
	 * would be wrong here — each notebook MDX cell has its own independent
	 * execution (per the issue), and the share viewer renders this same
	 * component for a guest with no query stores at all. This is a thin,
	 * store-free presentation over the pure parseCellset() helper.
	 */
	import type { QueryResult } from '$lib/api/query';
	import { parseCellset } from '$lib/views/cellsetUtils';
	import { parseFormattedCell } from '$lib/cellset/cellFormat';

	interface Props {
		result: QueryResult;
	}

	let { result }: Props = $props();

	let parsed = $derived(parseCellset(result));
</script>

{#if result.error}
	<div class="nb-grid__error">{result.error}</div>
{:else if parsed.dataRows.length === 0}
	<div class="nb-grid__empty">No rows returned.</div>
{:else}
	<div class="nb-grid">
		<table>
			<thead>
				{#each parsed.columnHeaderRows as headerRow, hi (hi)}
					<tr>
						{#if hi === 0}
							<th class="nb-grid__rowhead" colspan={Math.max(1, parsed.rowHeaderColCount)}></th>
						{/if}
						{#each headerRow.slice(parsed.rowHeaderColCount) as cell, ci (ci)}
							<th>{parseFormattedCell(cell.value).display}</th>
						{/each}
					</tr>
				{/each}
			</thead>
			<tbody>
				{#each parsed.bodyRows as rowHeaders, ri (ri)}
					<tr>
						{#each rowHeaders as cell, ci (ci)}
							<th class="nb-grid__rowhead">{parseFormattedCell(cell.value).display}</th>
						{/each}
						{#each parsed.dataRows[ri] as cell, ci (ci)}
							<td>{parseFormattedCell(cell.value).display}</td>
						{/each}
					</tr>
				{/each}
			</tbody>
		</table>
	</div>
{/if}

<style>
	.nb-grid {
		overflow: auto;
		max-height: 360px;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-md, 6px);
	}
	table {
		border-collapse: collapse;
		width: 100%;
		font-size: var(--fs-sm);
	}
	th,
	td {
		padding: 4px 8px;
		border-bottom: 1px solid hsl(var(--border));
		white-space: nowrap;
		text-align: right;
	}
	th {
		text-align: left;
		font-weight: var(--weight-semibold);
		background: hsl(var(--bg-subtle));
		position: sticky;
		top: 0;
	}
	.nb-grid__rowhead {
		text-align: left;
		background: hsl(var(--bg-subtle));
	}
	.nb-grid__error {
		color: hsl(var(--danger, var(--fg)));
		font-size: var(--fs-sm);
		padding: 0.5rem;
	}
	.nb-grid__empty {
		color: hsl(var(--fg-muted));
		font-size: var(--fs-sm);
		padding: 0.5rem;
	}
</style>
