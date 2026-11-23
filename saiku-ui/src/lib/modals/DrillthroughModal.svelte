<script lang="ts">
	import { untrack } from 'svelte';
	import Modal from '$lib/components/Modal.svelte';
	import { Button, Radio, Tooltip } from '$lib/components/ui';
	import type { SaikuDimension, SaikuMeasure } from '$lib/api/discover';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { isDrillthroughColumnDiscovered } from './drillthroughColumnMatch';

	/** Port of saiku-ui-legacy/js/saiku/views/DrillthroughModal.js. */

	/** One drillthrough-eligible column as reported by a column-discovery
	 *  endpoint (`GET .../drillthrough/columns`, saiku#774/#822). Only `name`
	 *  is used here, matched against `dimensions`/`measures` uniqueNames. */
	interface DiscoveredColumn {
		name: string;
		type: string;
	}

	interface RunArgs {
		dimensions: string[];
		measures: string[];
		maxRows: number;
		/** Present only when the "First rowset" mode is selected (saiku#823). */
		firstRowset?: number;
	}

	interface Props {
		dimensions: SaikuDimension[];
		measures: SaikuMeasure[];
		maxRows: number;
		open: boolean;
		/** Column discovery (saiku#774/#822) — called once when the modal opens.
		 *  When supplied, the dimension/measure lists are narrowed to just the
		 *  columns this specific query can drill through, instead of the full
		 *  cube schema. A rejected promise (or no prop at all) falls back to
		 *  showing every passed-in dimension/measure, unchanged. */
		discoverColumns?: () => Promise<DiscoveredColumn[]>;
		onRun: (opts: RunArgs) => void;
		onExportCsv: (opts: { dimensions: string[]; measures: string[]; firstRowset?: number }) => void;
		onCancel: () => void;
	}

	let {
		dimensions,
		measures,
		maxRows,
		open,
		discoverColumns,
		onRun,
		onExportCsv,
		onCancel
	}: Props = $props();
	let pickedDims = $state<Set<string>>(new Set());
	let pickedMeasures = $state<Set<string>>(new Set());
	let rows = $state<number>(untrack(() => maxRows));
	/** "maxRows" — Mondrian materialises the result then trims (cheap for a
	 *  small cellset). "firstRowset" — the warehouse short-circuits and
	 *  streams only the first N rows (cheap for a small N against a
	 *  multi-million-row fact table). See docs/AI-QUERY-API.md Step 5. */
	let rowBound = $state<'maxRows' | 'firstRowset'>('maxRows');
	/** null = no discovery attempted, or it failed/returned nothing usable —
	 *  show the full dimensions/measures props unfiltered. Non-null = narrow
	 *  the pickers to exactly these MDX-qualified column names. */
	let discoveredNames = $state<Set<string> | null>(null);
	let discovering = $state(false);

	$effect(() => {
		if (open) {
			pickedDims = new Set();
			pickedMeasures = new Set();
			rows = maxRows;
			rowBound = 'maxRows';
			discoveredNames = null;
			if (discoverColumns) {
				discovering = true;
				discoverColumns()
					.then((cols) => {
						discoveredNames = new Set(cols.map((c) => c.name));
					})
					.catch(() => {
						// Discovery is a narrowing convenience, not a hard requirement —
						// fall back to the full dimensions/measures lists on failure.
						discoveredNames = null;
					})
					.finally(() => {
						discovering = false;
					});
			}
		}
	});

	const visibleDimensions = $derived(
		discoveredNames
			? dimensions.filter((d) => isDrillthroughColumnDiscovered(d.uniqueName, discoveredNames!))
			: dimensions
	);
	const visibleMeasures = $derived(
		discoveredNames
			? measures.filter((m) => isDrillthroughColumnDiscovered(m.uniqueName, discoveredNames!))
			: measures
	);

	function toggleDim(un: string) {
		if (pickedDims.has(un)) pickedDims.delete(un);
		else pickedDims.add(un);
		pickedDims = new Set(pickedDims);
	}

	function toggleMeasure(un: string) {
		if (pickedMeasures.has(un)) pickedMeasures.delete(un);
		else pickedMeasures.add(un);
		pickedMeasures = new Set(pickedMeasures);
	}

	function runArgs(): RunArgs {
		return {
			dimensions: Array.from(pickedDims),
			measures: Array.from(pickedMeasures),
			maxRows: rows,
			...(rowBound === 'firstRowset' ? { firstRowset: rows } : {})
		};
	}

	function exportArgs() {
		const { dimensions: d, measures: m, firstRowset } = runArgs();
		return { dimensions: d, measures: m, firstRowset };
	}
</script>

<Modal title={i18n.t('modal.drillthrough.title')} {open} size="lg" onClose={onCancel}>
	<div class="cols">
		<section>
			<h3>{i18n.t('panels.dimensions')}</h3>
			<ul class="list">
				{#each visibleDimensions as d}
					<li>
						<label>
							<input
								type="checkbox"
								checked={pickedDims.has(d.uniqueName)}
								onchange={() => toggleDim(d.uniqueName)}
							/>
							{d.caption || d.name}
						</label>
					</li>
				{/each}
			</ul>
		</section>
		<section>
			<h3>{i18n.t('panels.measures')}</h3>
			<ul class="list">
				{#each visibleMeasures as m}
					<li>
						<label>
							<input
								type="checkbox"
								checked={pickedMeasures.has(m.uniqueName)}
								onchange={() => toggleMeasure(m.uniqueName)}
							/>
							{m.caption || m.name}
						</label>
					</li>
				{/each}
			</ul>
		</section>
	</div>
	{#if discovering}
		<p class="discovering">{i18n.t('modal.drillthrough.columnsLoading')}</p>
	{/if}
	<div class="row-bound">
		<span class="field__label">{i18n.t('modal.drillthrough.rowBound')}</span>
		<div class="row-bound__options">
			<Tooltip text={i18n.t('modal.drillthrough.rowBound.maxRowsHint')}>
				<Radio bind:group={rowBound} value="maxRows">
					{#snippet label()}{i18n.t('modal.drillthrough.rowBound.maxRows')}{/snippet}
				</Radio>
			</Tooltip>
			<Tooltip text={i18n.t('modal.drillthrough.rowBound.firstRowsetHint')}>
				<Radio bind:group={rowBound} value="firstRowset">
					{#snippet label()}{i18n.t('modal.drillthrough.rowBound.firstRowset')}{/snippet}
				</Radio>
			</Tooltip>
		</div>
	</div>
	<label class="field">
		<span class="field__label">
			{rowBound === 'firstRowset'
				? i18n.t('modal.drillthrough.rowBound.firstRowset')
				: i18n.t('modal.drillthrough.maxRows')}
		</span>
		<input class="field__input" type="number" min="1" bind:value={rows} />
	</label>
	{#snippet footer()}
		<Button variant="outline" onclick={onCancel}>{i18n.t('modal.cancel')}</Button>
		<Button variant="outline" onclick={() => onExportCsv(exportArgs())}
			>{i18n.t('modal.drillthrough.exportCsv')}</Button
		>
		<Button onclick={() => onRun(runArgs())}>{i18n.t('toolbar.run')}</Button>
	{/snippet}
</Modal>

<style>
	.cols {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--space-3);
		margin-bottom: var(--space-4);
	}
	h3 {
		margin: 0 0 var(--space-2);
		font-size: var(--fs-sm);
		color: hsl(var(--fg-muted));
		text-transform: uppercase;
		letter-spacing: 0.04em;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		max-height: 40vh;
		overflow: auto;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
	}
	.list li + li {
		border-top: 1px solid hsl(var(--border));
	}
	.list label {
		display: flex;
		gap: var(--space-2);
		padding: var(--space-1) var(--space-3);
		cursor: pointer;
	}
	.list label:hover {
		background: hsl(var(--bg-subtle));
	}
	.discovering {
		margin: 0 0 var(--space-3);
		font-size: var(--fs-sm);
		color: hsl(var(--fg-muted));
	}
	.row-bound {
		margin-bottom: var(--space-3);
	}
	.row-bound__options {
		display: flex;
		gap: var(--space-4);
		margin-top: var(--space-1);
	}
</style>
