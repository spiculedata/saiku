<script lang="ts">
	/*
	 * Right-pane detail drawer for the currently-selected schema node.
	 *
	 * Resolves the draft node at `selectedPath` and renders editable caption /
	 * description inputs (plus an aggregator dropdown for measures). All
	 * mutations are surfaced via `onEdit(path, field, value)` — the drawer does
	 * not know about suggestion ops; the parent (D5 wizard) translates edits
	 * into RenameOp / AggregatorOp calls against the schema-gen store.
	 */
	import type { DraftView } from '$lib/api/schemaGen';
	import { provenanceBadgeLabel, resolveDraftNode } from './schemaTree.model';

	type EditField = 'caption' | 'description' | 'aggregator';

	interface Props {
		draft: DraftView | null;
		selectedPath: string | null;
		onEdit: (path: string, field: EditField, value: string) => void;
	}

	let { draft, selectedPath, onEdit }: Props = $props();

	const resolved = $derived(selectedPath !== null ? resolveDraftNode(draft, selectedPath) : null);

	// Flattened accessors for the common fields. Non-measure nodes don't have an
	// aggregator; guarded in the template.
	const node = $derived(resolved?.node ?? null);
	const kind = $derived(resolved?.kind ?? null);

	const AGGREGATORS = ['SUM', 'COUNT', 'AVG', 'DISTINCT_COUNT', 'MIN', 'MAX', 'COUNT_STAR'];

	function fireEdit(field: EditField, value: string) {
		if (selectedPath === null) return;
		onEdit(selectedPath, field, value);
	}
</script>

<aside class="drawer" aria-label="Node detail">
	{#if !resolved || !node}
		<p class="text-fg-muted">Select a node in the tree to edit it.</p>
	{:else}
		<header class="flex flex-col gap-0.5 border-b border-border pb-2">
			<h3 class="m-0 text-lg font-semibold">{node.caption ?? node.name}</h3>
			<p class="drawer__kind">{kind}</p>
			{#if node.provenance}
				<span
					class="drawer__badge drawer__badge--{node.provenance.source.toLowerCase()}"
					title={node.provenance.ruleId ?? undefined}
				>
					{provenanceBadgeLabel(node.provenance.source)}
					{#if node.provenance.ruleId}
						<span class="font-normal normal-case opacity-85"> · {node.provenance.ruleId}</span>
					{/if}
				</span>
			{/if}
		</header>

		<label class="flex flex-col gap-1">
			<span class="drawer__label">Caption</span>
			<input
				type="text"
				class="drawer__input"
				value={node.caption ?? ''}
				placeholder={node.name}
				oninput={(e) => fireEdit('caption', e.currentTarget.value)}
			/>
		</label>

		<label class="flex flex-col gap-1">
			<span class="drawer__label">Description</span>
			<textarea
				class="drawer__textarea"
				rows="3"
				value={node.description ?? ''}
				oninput={(e) => fireEdit('description', e.currentTarget.value)}></textarea>
		</label>

		{#if resolved.kind === 'measure'}
			<label class="flex flex-col gap-1">
				<span class="drawer__label">Aggregator</span>
				<select
					class="drawer__input"
					value={resolved.node.aggregator ?? ''}
					onchange={(e) => fireEdit('aggregator', e.currentTarget.value)}
				>
					{#each AGGREGATORS as agg (agg)}
						<option value={agg}>{agg}</option>
					{/each}
				</select>
			</label>
		{/if}

		<dl class="drawer__meta">
			<dt>Path</dt>
			<dd><code>{selectedPath}</code></dd>
			<dt>Name</dt>
			<dd>{node.name}</dd>
		</dl>
	{/if}
</aside>

<style>
	.drawer {
		font-family: var(--font-sans);
		font-size: var(--fs-sm);
		color: hsl(var(--fg));
		padding: var(--space-4);
		display: flex;
		flex-direction: column;
		gap: var(--space-3);
		overflow: auto;
	}
	.drawer__kind {
		margin: 0;
		color: hsl(var(--fg-muted));
		font-size: var(--fs-xs);
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}
	.drawer__badge {
		display: inline-block;
		align-self: flex-start;
		margin-top: var(--space-2);
		font-size: 10px;
		line-height: 1;
		padding: 3px 8px;
		border-radius: 999px;
		font-weight: var(--weight-semibold);
		text-transform: uppercase;
		letter-spacing: 0.03em;
		background: hsl(var(--fg-subtle));
		color: hsl(var(--primary-foreground));
	}
	.drawer__badge--user {
		background: hsl(var(--success));
		color: #fff;
	}
	.drawer__label {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-muted));
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}
	.drawer__input,
	.drawer__textarea {
		font: inherit;
		color: hsl(var(--fg));
		background: hsl(var(--bg));
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		padding: var(--space-2) var(--space-3);
	}
	.drawer__input:focus,
	.drawer__textarea:focus {
		outline: none;
		box-shadow: var(--focus-ring);
		border-color: hsl(var(--primary));
	}
	.drawer__textarea {
		resize: vertical;
		min-height: 72px;
	}
	.drawer__meta {
		display: grid;
		grid-template-columns: auto 1fr;
		gap: 2px var(--space-3);
		margin: 0;
		padding-top: var(--space-2);
		border-top: 1px solid hsl(var(--border));
		font-size: var(--fs-xs);
		color: hsl(var(--fg-muted));
	}
	.drawer__meta dt {
		font-weight: var(--weight-semibold);
	}
	.drawer__meta dd {
		margin: 0;
	}
	.drawer__meta code {
		font-family: var(--font-mono);
		font-size: var(--fs-xs);
	}
</style>
