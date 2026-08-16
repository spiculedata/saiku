<script lang="ts">
	import { selection } from '$lib/stores/selection.svelte';
	import { ossieQuery } from '$lib/stores/ossieQuery.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { Skeleton } from '$lib/design-system';
	import { Tooltip } from '$lib/components/ui';
	import { Calendar, Info, Lock, Sigma, Table2 } from '@lucide/svelte';

	interface Props {
		username: string;
	}

	let { username }: Props = $props();

	/**
	 * When the user picks an OSSIE connection in the CubePicker, fire the discover fetch.
	 * Guarded so we don't refetch on unrelated tab-switch reactivity — the store's own
	 * memoisation short-circuits when the loaded connection matches. Runs inside $effect
	 * because we need to react to selection changes; the store's loadModel is idempotent so
	 * this is safe under the Svelte 5 effect-discipline note.
	 */
	$effect(() => {
		if (selection.mode === 'ossie' && selection.ossie) {
			const conn = selection.ossie.connectionName;
			const model = selection.ossie.modelName;
			void ossieQuery.loadModel(username, conn, model);
		}
	});

	/**
	 * Drag payload MIME types. The canvas dropzones read the exact type to know which shelf
	 * accepts the drop (fields go on Rows/Columns/Filters; metrics go on Values).
	 */
	const FIELD_MIME = 'application/x-saiku-ossie-field';
	const METRIC_MIME = 'application/x-saiku-ossie-metric';

	function fieldDragStart(e: DragEvent, dataset: string, field: string) {
		if (!e.dataTransfer) return;
		e.dataTransfer.setData(FIELD_MIME, JSON.stringify({ dataset, field }));
		e.dataTransfer.effectAllowed = 'copy';
	}

	function metricDragStart(e: DragEvent, metric: string) {
		if (!e.dataTransfer) return;
		e.dataTransfer.setData(METRIC_MIME, JSON.stringify({ metric }));
		e.dataTransfer.effectAllowed = 'copy';
	}

	/** Handy for the fact-dataset picker below. */
	const factDatasets = $derived(ossieQuery.model?.datasets ?? []);
</script>

<div class="ossie-tree">
	{#if ossieQuery.loading}
		<Skeleton rows={6} />
	{:else if ossieQuery.error}
		<p class="callout callout--danger">{ossieQuery.error}</p>
	{:else if ossieQuery.model}
		<section class="ossie-tree__section">
			<header class="ossie-tree__label">
				Fact dataset
				<Tooltip
					side="right"
					text="The anchor table for this query. Metrics aggregate at its grain, and fields you add from other datasets are auto-joined onto it via the model's relationships. Pick the central fact table your metrics are built on — dimensions join out from it."
				>
					<button type="button" class="ossie-tree__info" aria-label="What is the fact dataset?">
						<Info size={13} aria-hidden="true" />
					</button>
				</Tooltip>
			</header>
			<select
				class="ossie-tree__select"
				value={ossieQuery.current?.factDataset ?? ''}
				onchange={(e) => ossieQuery.setFactDataset((e.currentTarget as HTMLSelectElement).value)}
			>
				<option value="">{i18n.t('ossie.tree.factPrompt') ?? 'Pick a fact dataset'}</option>
				{#each factDatasets as d}
					<option value={d.name}>{d.name}</option>
				{/each}
			</select>
			<p class="ossie-tree__hint">
				Metrics reference the fact dataset. Cross-dataset joins are auto-injected.
			</p>
		</section>

		<section class="ossie-tree__section">
			<header class="ossie-tree__label">Datasets</header>
			<ul class="ossie-tree__list">
				{#each ossieQuery.model.datasets as ds}
					<li class="ossie-tree__group">
						<div class="ossie-tree__row" title={ds.source ?? ds.name}>
							<Table2 size={14} class="ossie-tree__icon" />
							<span class="ossie-tree__group-name">{ds.name}</span>
							<span class="ossie-tree__group-count">{ds.fields.length}</span>
						</div>
						<ul class="ossie-tree__children">
							{#each ds.fields as f}
								<li
									class="ossie-tree__field"
									draggable="true"
									ondragstart={(e) => fieldDragStart(e, ds.name, f.name)}
									title={f.description ?? f.expression ?? f.name}
								>
									{#if f.time}<Calendar size={12} class="ossie-tree__icon-inline" />{/if}
									{#if f.pii}<Lock size={12} class="ossie-tree__icon-inline text-danger" />{/if}
									<span>{f.label ?? f.name}</span>
								</li>
							{/each}
							{#if ds.fields.length === 0}
								<li class="ossie-tree__empty">no fields declared</li>
							{/if}
						</ul>
					</li>
				{/each}
			</ul>
		</section>

		{#if ossieQuery.model.metrics.length > 0}
			<section class="ossie-tree__section">
				<header class="ossie-tree__label">Metrics</header>
				<ul class="ossie-tree__list">
					{#each ossieQuery.model.metrics as m}
						<li
							class="ossie-tree__metric"
							draggable="true"
							ondragstart={(e) => metricDragStart(e, m.name)}
							title={m.description ?? m.expression ?? m.name}
						>
							<Sigma size={12} class="ossie-tree__icon-inline" />
							<span>{m.name}</span>
							{#if m.aggregationKind}
								<span class="ossie-tree__badge">{m.aggregationKind}</span>
							{/if}
						</li>
					{/each}
				</ul>
			</section>
		{/if}

		{#if ossieQuery.model.relationships.length > 0}
			<section class="ossie-tree__section">
				<header class="ossie-tree__label">Relationships</header>
				<ul class="ossie-tree__list ossie-tree__list--dim">
					{#each ossieQuery.model.relationships as r}
						<li
							class="ossie-tree__relation"
							title={`${r.fromColumns.join(',')} → ${r.toColumns.join(',')}`}
						>
							<span>{r.from}</span>
							<span class="ossie-tree__arrow">→</span>
							<span>{r.to}</span>
						</li>
					{/each}
				</ul>
			</section>
		{/if}
	{:else if selection.mode === 'ossie'}
		<p class="ossie-tree__empty">Loading model…</p>
	{/if}
</div>

<style>
	.ossie-tree {
		display: flex;
		flex-direction: column;
		gap: var(--space-3);
	}
	.ossie-tree__section {
		display: flex;
		flex-direction: column;
		gap: var(--space-1);
	}
	.ossie-tree__label {
		display: flex;
		align-items: center;
		gap: var(--space-1);
		font-size: var(--fs-xs);
		font-weight: var(--weight-semibold);
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: hsl(var(--fg-muted));
		padding-bottom: var(--space-1);
	}
	.ossie-tree__info {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		padding: 0;
		border: 0;
		background: transparent;
		color: hsl(var(--fg-subtle));
		cursor: help;
		line-height: 0;
	}
	.ossie-tree__info:hover,
	.ossie-tree__info:focus-visible {
		color: hsl(var(--fg));
	}
	.ossie-tree__select {
		padding: 6px 8px;
		background: hsl(var(--bg));
		color: hsl(var(--fg));
		border: 1px solid hsl(var(--border-strong));
		border-radius: 4px;
		font-size: var(--fs-sm);
	}
	.ossie-tree__hint {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-subtle));
		margin: 0;
	}
	.ossie-tree__list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.ossie-tree__list--dim {
		color: hsl(var(--fg-subtle));
	}
	.ossie-tree__group {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.ossie-tree__row {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: var(--fs-sm);
		font-weight: var(--weight-medium);
	}
	.ossie-tree__group-name {
		flex: 1;
	}
	.ossie-tree__group-count {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-subtle));
	}
	.ossie-tree__children {
		list-style: none;
		padding-left: 22px;
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.ossie-tree__field,
	.ossie-tree__metric {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 3px 6px;
		border-radius: 3px;
		font-size: var(--fs-sm);
		cursor: grab;
		user-select: none;
	}
	.ossie-tree__field:hover,
	.ossie-tree__metric:hover {
		background: hsl(var(--bg-hover));
	}
	.ossie-tree__field:active,
	.ossie-tree__metric:active {
		cursor: grabbing;
	}
	.ossie-tree__badge {
		margin-left: auto;
		font-size: 10px;
		padding: 1px 6px;
		border-radius: 10px;
		background: var(--bg-accent-subtle, hsl(var(--bg-hover)));
		color: hsl(var(--fg-muted));
		text-transform: uppercase;
		letter-spacing: 0.04em;
	}
	.ossie-tree__relation {
		display: flex;
		gap: 6px;
		font-size: var(--fs-xs);
		padding: 3px 6px;
	}
	.ossie-tree__arrow {
		color: hsl(var(--fg-subtle));
	}
	.ossie-tree__empty {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-subtle));
		padding-left: 22px;
	}
</style>
